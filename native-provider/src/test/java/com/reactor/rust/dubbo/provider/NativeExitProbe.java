package com.reactor.rust.dubbo.provider;

import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.*;
import static com.reactor.rust.dubbo.provider.NativeWire.check;

/** Natural process exit, not System.exit or forced interruption of business code. */
public final class NativeExitProbe {
    public static void main(String[] ignored) throws Exception {
        int port;try(var free=new ServerSocket(0)){port=free.getLocalPort();}
        var entered=new CountDownLatch(1);var neverReleased=new CountDownLatch(1);
        var provider=NativeProviderFactory.start(new NativeProviderFactory.Options(port,1,1,1,128*1024,4000,0,64),
                List.of(CatalogDefinition__NativeProvider.bind(()->{
                    entered.countDown();
                    try{neverReleased.await();}catch(InterruptedException e){throw new AssertionError("business interrupted",e);}
                    return new byte[0];
                },PlainDubboProvider.ServiceExecutionConfig.bounded(1))),null);
        var wire=new Thread(()->{
            try{NativeWire.call(port,CatalogDefinition__NativeProvider.CONTRACT,"getNestedCatalogJson");}
            catch(java.io.IOException expectedOnShutdown){ /* no replay */ }
        },"exit-proof-client");
        wire.setDaemon(true);wire.start();
        check(entered.await(3,TimeUnit.SECONDS),"native business callback entered");
        long start=System.nanoTime();provider.close();
        check(System.nanoTime()-start<TimeUnit.SECONDS.toNanos(2),"close deadline bounded");
        check(provider.activeCodecContexts()==1,"business still running, not forcibly stopped");
        System.out.println("DAEMON_NATURAL_EXIT_PASS activeCallback=1");
        // Returning from main must exit despite the Rust-attached Java callback still waiting.
    }
}
