package com.reactor.rust.dubbo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "reactor.test.native-dubbo", matches = "true")
class NativeDubboCallbackIsolationTest {
    @Test
    void blockedJavaCompletionDoesNotBlockAnotherConnection() throws Exception {
        var firstSeen = new CountDownLatch(1);
        var sendFirst = new CountDownLatch(1);
        var callbackEntered = new CountDownLatch(1);
        var releaseCallback = new CountDownLatch(1);
        var secondSeen = new CountDownLatch(1);
        var received = new AtomicInteger();
        try (var server = new ServerSocket(0); var peers = Executors.newFixedThreadPool(2)) {
            Thread acceptor = Thread.ofPlatform().daemon().start(() -> {
                try {
                    while (!server.isClosed()) {
                        Socket peer = server.accept();
                        peers.execute(() -> {
                            try (peer) {
                                peer.setTcpNoDelay(true);
                                var in = new DataInputStream(peer.getInputStream());
                                var out = new DataOutputStream(peer.getOutputStream());
                                while (true) {
                                    byte[] header = in.readNBytes(16);
                                    if (header.length == 0) return;
                                    if (header.length != 16) throw new java.io.EOFException();
                                    int length = ByteBuffer.wrap(header).getInt(12);
                                    if (length != 1 || in.readNBytes(length).length != length)
                                        throw new java.io.IOException("Unexpected request");
                                    int call = received.incrementAndGet();
                                    if (call == 1) {
                                        firstSeen.countDown();
                                        if (!sendFirst.await(3, TimeUnit.SECONDS)) return;
                                    } else if (call == 2) secondSeen.countDown();
                                    header[2] = 2;
                                    header[3] = 20;
                                    ByteBuffer.wrap(header).putInt(12, 3);
                                    out.write(header);
                                    out.write(new byte[]{1, 2, 3});
                                    out.flush();
                                }
                            } catch (java.io.IOException closed) {
                                // Closing the client retires its bounded connections.
                            } catch (InterruptedException stopped) {
                                Thread.currentThread().interrupt();
                            }
                        });
                    }
                } catch (java.io.IOException closed) {
                    if (!server.isClosed()) throw new AssertionError(closed);
                }
            });
            NativeDubboBridge.configureAsync(2, 16, "tokio-demux");
            int client = NativeDubboBridge.createClient(3000, 16, 1024, 2);
            try {
                NativeDubboBridge.updateProviders(client, "127.0.0.1:" + server.getLocalPort());
                var first = NativeDubboBridge.invokeAsync(client, new byte[]{1}, 3000);
                assertTrue(firstSeen.await(2, TimeUnit.SECONDS));
                var callback = first.thenRun(() -> {
                    callbackEntered.countDown();
                    try {
                        if (!releaseCallback.await(3, TimeUnit.SECONDS)) throw new AssertionError("Callback not released");
                    } catch (InterruptedException stopped) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(stopped);
                    }
                });
                sendFirst.countDown();
                assertTrue(callbackEntered.await(2, TimeUnit.SECONDS));
                var second = NativeDubboBridge.invokeAsync(client, new byte[]{1}, 3000);
                assertTrue(secondSeen.await(2, TimeUnit.SECONDS), "Java continuation blocked socket progress");
                assertArrayEquals(new byte[]{1, 2, 3}, second.get(2, TimeUnit.SECONDS),
                        "Independent completion capacity was stolen by the blocked callback");
                releaseCallback.countDown();
                callback.get(3, TimeUnit.SECONDS);
                assertArrayEquals(new byte[]{1, 2, 3}, second.get(3, TimeUnit.SECONDS));
                assertArrayEquals(new byte[]{1, 2, 3}, NativeDubboBridge.invokeAsync(
                        client, new byte[]{1}, 3000).get(3, TimeUnit.SECONDS));
                assertEquals(3, received.get(), "Business calls must not be replayed");
            } finally {
                sendFirst.countDown();
                releaseCallback.countDown();
                NativeDubboBridge.closeClient(client);
                server.close();
                acceptor.join(2000);
            }
        }
    }
}
