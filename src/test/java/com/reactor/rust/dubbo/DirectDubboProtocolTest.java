package com.reactor.rust.dubbo;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class DirectDubboProtocolTest {

    @Test
    void directDubboProtocolCanCreateLazyInvokerWithoutConfigApi() {
        ExtensionLoader<Protocol> loader = ApplicationModel.defaultModel().getExtensionLoader(Protocol.class);
        Protocol protocol = loader.getExtension("dubbo", false);
        URL url = new URL("dubbo", "127.0.0.1", 20990, RemotePingService.class.getName(), Map.of(
                "interface", RemotePingService.class.getName(),
                "application", "test",
                "serialization", "hessian2",
                "client", "netty4",
                "timeout", "10",
                "lazy", "true"))
                .setScopeModel(ApplicationModel.defaultModel());

        Invoker<RemotePingService> invoker = protocol.refer(RemotePingService.class, url);
        try {
            assertNotNull(invoker);
        } finally {
            invoker.destroy();
        }
    }

    interface RemotePingService {
        String ping(String value);
    }
}
