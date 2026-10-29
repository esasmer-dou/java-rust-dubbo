package com.reactor.rust.dubbo.provider;

import com.reactor.rust.dubbo.runtime.NativeDubboBridge;
import java.net.*;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static com.reactor.rust.dubbo.provider.NativeWire.check;

/** Bounded functional admission proof, not a load test. */
public final class NativeCapacityProbe {
    public static void main(String[] ignored) throws Exception {
        int port; try (var free = new ServerSocket(0)) { port = free.getLocalPort(); }
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var owner = new AtomicReference<NativeProviderFactory.Running>();
        var calls = new AtomicInteger(); var nestedRejected = new AtomicInteger();
        var provider = NativeProviderFactory.start(new NativeProviderFactory.Options(port,1,1,1,128*1024,4000,4000,64),
                List.of(CatalogDefinition__NativeProvider.bind(() -> {
                    calls.incrementAndGet();
                    ByteBuffer output = ByteBuffer.allocateDirect(32); output.put(0,(byte)0x55);
                    try {
                        owner.get().dispatch(1,1,ByteBuffer.allocateDirect(0),0,0,output);
                        throw new AssertionError("Nested context borrow unexpectedly succeeded");
                    } catch (RejectedExecutionException expected) {
                        check(output.get(0) == (byte)0x55, "reentrant borrow did not touch borrowed output"); nestedRejected.incrementAndGet();
                    }
                    entered.countDown();
                    try { check(release.await(5,TimeUnit.SECONDS),"capacity release deadline"); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                    return new byte[]{1,2,3};
                }, PlainDubboProvider.ServiceExecutionConfig.bounded(2))), null);
        owner.set(provider);
        check(metric(NativeDubboBridge.metricsJson(),"providerConnectionLimit")==2,"local connection limit gauge");
        check(metric(NativeDubboBridge.metricsJson(),"providerConnectionAdmissionLimit")==256,"global connection limit gauge");
        check(metric(NativeDubboBridge.metricsJson(),"providerResponseLimit")==2,"local response limit gauge");
        check(metric(NativeDubboBridge.metricsJson(),"providerResponseAdmissionLimit")==256,"global response limit gauge");
        try {
        try (var pipe = new NativeWire.Pipeline(port)) {
            long first = pipe.send(CatalogDefinition__NativeProvider.CONTRACT,"getNestedCatalogJson");
            check(entered.await(3,TimeUnit.SECONDS),"capacity first entered");
            check(provider.activeCodecContexts() == 1,"only codec slot exclusively held");
            long queued = pipe.send(CatalogDefinition__NativeProvider.CONTRACT,"getNestedCatalogJson");
            long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while (metric(NativeDubboBridge.metricsJson(),"providerAccepted") < 2 && System.nanoTime()<deadline) Thread.sleep(5);
            check(metric(NativeDubboBridge.metricsJson(),"providerAccepted") == 2,"one active plus one queued accepted");
            check(calls.get() == 1,"queued request not executing");
            long third = pipe.send(CatalogDefinition__NativeProvider.CONTRACT,"getNestedCatalogJson");
            var rejected = pipe.receive();
            check(rejected.id()==third && rejected.reply().status() == 100,"native lane/queue overload status");
            check(calls.get() == 1,"native overload not dispatched or retried");
            release.countDown();
            var one = pipe.receive(); var two = pipe.receive();
            check(java.util.Set.of(one.id(),two.id()).equals(java.util.Set.of(first,queued)) && one.reply().flag()==1 && two.reply().flag()==1,"both admitted calls complete");
            check(calls.get()==2 && nestedRejected.get()==2,"CAS slot restored; reentrant borrow rejected twice");
            check(provider.activeCodecContexts()==0,"all contexts returned");
            // The pipeline uses one TCP connection; the second is available for header rejection.
            try (var socket = new Socket("127.0.0.1",port)) {
                socket.setSoTimeout(2000); var frame = new DataOutputStream(socket.getOutputStream());
                frame.writeShort(0xdabb); frame.writeByte(0xc2); frame.writeByte(0); frame.writeLong(99); frame.writeInt(128*1024+1); frame.flush();
                check(socket.getInputStream().read() == -1,"oversized frame connection closed");
            }
            check(calls.get()==2,"oversized frame not invoked");
        } finally { release.countDown(); }
            awaitConnections(0);
            try (var one = new Socket("127.0.0.1",port); var two = new Socket("127.0.0.1",port)) {
                awaitConnections(2);
                try (var excess = new Socket("127.0.0.1",port)) {
                    excess.setSoTimeout(2000); check(excess.getInputStream().read()==-1,"connection cap rejected before task/body");
                }
                one.close(); awaitConnections(1);
                check(metric(NativeDubboBridge.metricsJson(),"providerConnectionAdmissionRejections")>=1,"connection rejection gauge");
                check(NativeWire.call(port,CatalogDefinition__NativeProvider.CONTRACT,"getNestedCatalogJson").flag()==1,"connection permit recovered");
            }
            awaitConnections(0);
            check(calls.get()==3 && nestedRejected.get()==3,"recovery not replay");
            System.out.println("CAPACITY_PASS active=1 queued=1 rejected=1 connections=2 recovered=1 calls=3 nestedRejected=3");
        } finally { release.countDown(); provider.close(); }
    }
    private static void awaitConnections(long expected) throws InterruptedException {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(metric(NativeDubboBridge.metricsJson(),"providerActiveConnections")!=expected && System.nanoTime()<deadline) Thread.sleep(5);
        check(metric(NativeDubboBridge.metricsJson(),"providerActiveConnections")==expected,"connection count "+expected);
    }
    static long metric(String json,String name) {
        String key="\""+name+"\":"; int start=json.indexOf(key); check(start>=0,"metric exists: "+name); start+=key.length();
        int end=start; while(end<json.length() && Character.isDigit(json.charAt(end))) end++;
        return Long.parseLong(json.substring(start,end));
    }
}
