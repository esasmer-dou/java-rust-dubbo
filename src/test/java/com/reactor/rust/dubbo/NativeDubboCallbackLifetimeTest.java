package com.reactor.rust.dubbo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.lang.management.ManagementFactory;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Real JNI/TCP test. Each transport is run in a separate JVM against the candidate native binary. */
@EnabledIfSystemProperty(named = "reactor.test.native-dubbo", matches = "true")
class NativeDubboCallbackLifetimeTest {
    @Test
    void callbacksReuseBoundedJvmAttachmentsAndRecoverAfterErrors() throws Exception {
        try (ServerSocket server = new ServerSocket(0);
             var peers = Executors.newFixedThreadPool(2)) {
            Thread acceptor = Thread.ofPlatform().daemon().start(() -> {
                try {
                    while (!server.isClosed()) {
                        Socket peer = server.accept();
                        peers.execute(() -> respond(peer));
                    }
                } catch (java.io.IOException closed) {
                    if (!server.isClosed()) throw new AssertionError(closed);
                }
            });
            NativeDubboBridge.configureAsync(2, 16,
                    System.getProperty("reactor.test.native-dubbo.transport", "blocking"));
            int client = NativeDubboBridge.createClient(1000, 16, 1024, 2);
            try {
                NativeDubboBridge.updateProviders(client, "127.0.0.1:" + server.getLocalPort());
                for (int i=0; i<20; i++) invoke(client);
                long started = ManagementFactory.getThreadMXBean().getTotalStartedThreadCount();
                for (int i=0; i<2000; i++) invoke(client);
                long extra = ManagementFactory.getThreadMXBean().getTotalStartedThreadCount() - started;
                assertTrue(extra <= 4, "Per-response JVM attachment churn: " + extra);
                assertThrows(ExecutionException.class, () -> NativeDubboBridge.invokeAsync(
                        client, new byte[]{0}, 1000).get(3, TimeUnit.SECONDS));
                invoke(client);
            } finally {
                NativeDubboBridge.closeClient(client);
                server.close();
                acceptor.join(2000);
            }
        }
    }

    private static void invoke(int client) throws Exception {
        assertArrayEquals(new byte[]{1,2,3}, NativeDubboBridge.invokeAsync(
                client, new byte[]{1}, 1000).get(3, TimeUnit.SECONDS));
    }

    static void respondJson(Socket peer) {
        respond(peer, new byte[]{(byte) 0x91, 0x22, '{', '}'});
    }

    private static void respond(Socket peer) {
        respond(peer, new byte[]{1, 2, 3});
    }

    private static void respond(Socket peer, byte[] response) {
        try (peer) {
            peer.setTcpNoDelay(true);
            var in = new DataInputStream(peer.getInputStream());
            var out = new DataOutputStream(peer.getOutputStream());
            while (true) {
                byte[] header = in.readNBytes(16);
                if (header.length == 0) return;
                if (header.length != 16) throw new java.io.EOFException();
                int length = ByteBuffer.wrap(header).getInt(12);
                if (length < 1 || length > 1024) throw new java.io.IOException("Invalid request size");
                byte[] request = in.readNBytes(length);
                if (request.length != length) throw new java.io.EOFException();
                header[2] = 2;
                header[3] = (byte)(request[0] == 0 ? 70 : 20);
                ByteBuffer.wrap(header).putInt(12, response.length);
                out.write(header);
                out.write(response);
                out.flush();
            }
        } catch (java.io.IOException closed) {
            // Client shutdown and error responses may retire a connection.
        }
    }
}
