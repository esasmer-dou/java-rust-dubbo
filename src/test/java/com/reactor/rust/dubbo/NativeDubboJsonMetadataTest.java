package com.reactor.rust.dubbo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "reactor.test.native-dubbo", matches = "true")
class NativeDubboJsonMetadataTest {
    @Test
    void jsonMetadataIsUnchangedAndReusedAcrossRealJniResponses() throws Exception {
        try (var server = new ServerSocket(0); var peers = Executors.newFixedThreadPool(2)) {
            Thread acceptor = Thread.ofPlatform().daemon().start(() -> {
                try {
                    while (!server.isClosed()) {
                        Socket peer = server.accept();
                        peers.execute(() -> NativeDubboCallbackLifetimeTest.respondJson(peer));
                    }
                } catch (java.io.IOException stopped) {
                    if (!server.isClosed()) throw new AssertionError(stopped);
                }
            });
            NativeDubboBridge.configureAsync(2, 16, "tokio-demux");
            int client = NativeDubboBridge.createClient(1000, 16, 1024, 2);
            try {
                NativeDubboBridge.updateProviders(client, "127.0.0.1:" + server.getLocalPort());
                var first = invoke(client);
                var second = invoke(client);
                assertTrue(first.nativeId() > 0);
                assertTrue(second.nativeId() > 0);
                assertNotEquals(first.nativeId(), second.nativeId());
                assertEquals(200, first.statusCode());
                assertEquals("application/json; charset=utf-8", first.contentType());
                assertEquals("", first.headers());
                assertSame(first.contentType(), second.contentType(), "New JNI String allocated per response");
                assertSame(first.headers(), second.headers(), "New empty header String allocated per response");
                assertEquals(0, NativeDubboBridge.pendingCountForTest());
            } finally {
                NativeDubboBridge.closeClient(client);
                server.close();
                acceptor.join(2000);
            }
        }
    }

    private static NativeResponseHandle invoke(int client) throws Exception {
        return NativeDubboBridge.invokeByteArrayNoArgsNativeJsonAsync(
                client, "sample.Service", null, null, "get", 1000).get(3, TimeUnit.SECONDS);
    }
}
