package com.reactor.rust.dubbo;

import com.reactor.rust.bridge.NativeBridge;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "reactor.test.native-dubbo", matches = "true")
class NativeDubboUnclaimedResponseTest {
    @Test void cancelledCallReleasesLateNativeBodyAndDoesNotPoisonTheCallbackWorker() throws Exception {
        try (var server = new ServerSocket(0); var peerWorker = Executors.newSingleThreadExecutor()) {
            var received = new CountDownLatch(1);
            var respond = new CountDownLatch(1);
            var worker = peerWorker.submit(() -> {
                try (var socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    socket.setTcpNoDelay(true);
                    var in = new DataInputStream(socket.getInputStream());
                    var out = new DataOutputStream(socket.getOutputStream());
                    for (int request = 0; request < 2; request++) {
                        byte[] header = in.readNBytes(16);
                        assertEquals(16, header.length);
                        int length = ByteBuffer.wrap(header).getInt(12);
                        assertTrue(length > 0 && length < 4096);
                        assertEquals(length, in.readNBytes(length).length);
                        if (request == 0) {
                            received.countDown();
                            assertTrue(respond.await(3, TimeUnit.SECONDS));
                        }
                        header[2] = 2; header[3] = 20;
                        ByteBuffer.wrap(header).putInt(12, 4);
                        out.write(header); out.write(new byte[]{(byte) 0x91, 0x22, '{', '}'}); out.flush();
                    }
                }
                return null;
            });
            NativeDubboBridge.configureAsync(2, 16, "tokio-demux");
            int client = NativeDubboBridge.createClient(1000, 16, 1024, 1);
            try {
                NativeDubboBridge.updateProviders(client, "127.0.0.1:" + server.getLocalPort());
                int baseline = registeredResponses();
                var cancelled = NativeDubboBridge.invokeByteArrayNoArgsNativeJsonAsync(
                        client, "sample.Service", null, null, "get", 1000);
                assertTrue(received.await(3, TimeUnit.SECONDS));
                assertTrue(cancelled.cancel(false));
                respond.countDown();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (NativeDubboBridge.pendingCountForTest() != 0 && System.nanoTime() < deadline) Thread.sleep(5);
                assertEquals(0, NativeDubboBridge.pendingCountForTest());
                // A subsequent callback completes after the orphan's release on this worker.
                assertArrayEquals(new byte[]{'{', '}'}, NativeDubboBridge.invokeByteArrayNoArgsAsync(
                        client, "sample.Service", null, null, "get", 1000).get(3, TimeUnit.SECONDS));
                worker.get(3, TimeUnit.SECONDS);
                deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (registeredResponses() != baseline && System.nanoTime() < deadline) Thread.sleep(5);
                assertEquals(baseline, registeredResponses(), "Cancelled response retained native body ownership");
            } finally {
                respond.countDown();
                NativeDubboBridge.closeClient(client);
            }
        }
    }

    private static int registeredResponses() {
        var match = Pattern.compile("\"static_responses\":\\{\"count\":(\\d+)")
                .matcher(NativeBridge.nativeMemoryDiagnosticsJson());
        assertTrue(match.find());
        return Integer.parseInt(match.group(1));
    }
}
