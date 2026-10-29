package com.reactor.rust.dubbo.provider;

import com.reactor.rust.dubbo.runtime.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static com.reactor.rust.dubbo.provider.NativeWire.check;

/** Direct ABI pressure proof so Java factory limits cannot mask a missing native byte cap. */
public final class NativeBudgetProbe {
    public static void main(String[] ignored) throws Exception {
        int port; try(var free=new ServerSocket(0)){port=free.getLocalPort();}
        var contract=CatalogDefinition__NativeProvider.CONTRACT;
        var runtime=new NativeDubboRuntime(DubboClientOptions.microDefaults(),new DubboRuntimeOptions(1,1,16,256*1024));
        runtime.registerProviderMethod(1,1,contract.serviceType().getName(),"","0.0.0","getNestedCatalogJson","()[B","budget",128);
        NativeDubboDispatcher dispatcher=(service,method,request,length,handle,response)->{
            var out=new Hessian2Output();
            try{out.attach(response,handle);out.writeInt(1);out.writeBinary(new byte[]{9});return out.position();}
            finally{out.detach();}
        };
        try {runtime.startProvider(port,1,1,127,33*1024*1024,6000,1000,dispatcher);throw new AssertionError("impossible startup payload accepted");}
        catch(DubboNativeException expected){check(expected.getMessage().contains("32 MiB wire budget"),"clear startup payload error");}
        check(!runtime.providerRunning(),"invalid startup did not bind");
        runtime.startProvider(port,1,1,127,8*1024*1024,6000,1000,dispatcher);
        var sockets=new ArrayList<Socket>();
        try {
            for(int i=0;i<33;i++){
                var socket=new Socket("127.0.0.1",port);socket.setSoTimeout(30);sockets.add(socket);
                var frame=new DataOutputStream(socket.getOutputStream());
                frame.writeShort(0xdabb);frame.writeByte(0xc2);frame.writeByte(0);frame.writeLong(i+1);frame.writeInt(1024*1024);frame.flush();
                if(i<32)awaitMetric("providerWireBytesUsed",(i+1L)*1024*1024);
            }
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(NativeCapacityProbe.metric(NativeDubboBridge.metricsJson(),"providerRejected")<1 && System.nanoTime()<deadline)Thread.sleep(5);
            check(NativeCapacityProbe.metric(NativeDubboBridge.metricsJson(),"providerRejected")==1,"exact one byte-budget rejection below connection cap 128");
            check(NativeCapacityProbe.metric(NativeDubboBridge.metricsJson(),"providerActiveConnections")==32,"exact 32 partial 1 MiB frames admitted");
            check(NativeCapacityProbe.metric(NativeDubboBridge.metricsJson(),"providerWireBytesLimit")==33554432,"wire limit gauge");
            check(NativeCapacityProbe.metric(NativeDubboBridge.metricsJson(),"providerWireBytesUsed")==33554432,"wire used gauge");
            check(NativeCapacityProbe.metric(NativeDubboBridge.metricsJson(),"providerWireRejections")==1,"wire rejection gauge");
            int closed=0;
            for(var socket:sockets){
                try{if(socket.getInputStream().read()==-1)closed++;}
                catch(SocketTimeoutException retainedPartialBody){ /* bounded read still waiting for body */ }
                catch(SocketException rejected){closed++;}
            }
            check(closed==1,"33rd declared MiB rejected without body allocation");
            for(var socket:sockets)socket.close();sockets.clear();
            deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(NativeCapacityProbe.metric(NativeDubboBridge.metricsJson(),"providerActiveConnections")!=0 && System.nanoTime()<deadline)Thread.sleep(5);
            check(NativeCapacityProbe.metric(NativeDubboBridge.metricsJson(),"providerActiveConnections")==0,"partial-read disconnects released connection/byte permits");
            awaitMetric("providerWireBytesUsed",0);
            var reply=NativeWire.call(port,contract,"getNestedCatalogJson");
            check(reply.flag()==1 && Arrays.equals((byte[])reply.value(),new byte[]{9}),"valid RPC after full budget reclaimed");
            System.out.println("BYTE_BUDGET_PASS limit=33554432 partialFrames=32 rejected=1 recoveredRpc=1");
        } finally {for(var socket:sockets)socket.close();runtime.close();}
    }
    private static void awaitMetric(String name,long expected)throws InterruptedException{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while(NativeCapacityProbe.metric(NativeDubboBridge.metricsJson(),name)!=expected && System.nanoTime()<deadline)Thread.sleep(2);
        check(NativeCapacityProbe.metric(NativeDubboBridge.metricsJson(),name)==expected,name+"="+expected);
    }
}
