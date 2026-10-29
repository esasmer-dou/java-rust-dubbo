package com.reactor.rust.dubbo.provider;

import java.net.ServerSocket;
import java.util.List;
import static com.reactor.rust.dubbo.provider.NativeWire.check;

/** Manual archived-library negative proof, run with the previous ABI-3 DLL, not the new engine. */
public final class NativeLegacyEngineProbe {
    public static void main(String[] ignored)throws Exception{
        int port;try(var free=new ServerSocket(0)){port=free.getLocalPort();}
        try{
            NativeProviderFactory.start(NativeProviderFactory.Options.defaults(port),List.of(CatalogDefinition__NativeProvider.bind(()->{
                throw new AssertionError("legacy business entered");
            },PlainDubboProvider.ServiceExecutionConfig.bounded(1))),null);
            throw new AssertionError("legacy ABI-3 engine accepted");
        }catch(IllegalStateException expected){check(expected.getMessage().contains("bounded ABI-3 engine"),"clear native capability failure");}
        try(var unbound=new ServerSocket(port)){check(unbound.isBound(),"failure occurred before listener bind");}
        System.out.println("LEGACY_ENGINE_REJECTED_BEFORE_BIND");
    }
}
