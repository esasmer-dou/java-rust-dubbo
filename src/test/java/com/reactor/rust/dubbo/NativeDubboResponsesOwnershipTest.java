package com.reactor.rust.dubbo;

import com.reactor.rust.bridge.NativeBridge;
import com.reactor.rust.dubbo.http.DubboResponses;
import com.reactor.rust.http.RawResponse;
import com.reactor.rust.http.ResponseEntity;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.parallel.Isolated;

import static org.junit.jupiter.api.Assertions.*;

/** Real JNI ownership checks; run in a fresh JVM against the candidate native library. */
@EnabledIfSystemProperty(named = "reactor.test.native-dubbo", matches = "true")
@Isolated("Native response diagnostics and Dubbo async configuration are process-global")
class NativeDubboResponsesOwnershipTest {
    private static final Pattern RESPONSE_COUNT = Pattern.compile("\"static_responses\":\\{\"count\":(\\d+)");

    @Test
    void throwingAdapterDiscardsItsNativeHandle() throws Exception {
        assertThrowingAdapterReclaimsOwnership(false, 0);
    }

    @Test
    void throwingAdapterDiscardsItsNativeEntity() throws Exception {
        assertThrowingAdapterReclaimsOwnership(false, 1);
    }

    @Test
    void throwingAdapterClosesItsOwnedBodyIdempotently() throws Exception {
        assertThrowingAdapterReclaimsOwnership(false, 2);
    }

    @Test
    void cancelledReplyStillDiscardsALateHandleWhenItsAdapterThrows() throws Exception {
        assertThrowingAdapterReclaimsOwnership(true, 0);
    }

    private static void assertThrowingAdapterReclaimsOwnership(boolean cancel, int carrier) throws Exception {
        try (var provider = new JsonProvider()) {
            int client = createClient(provider);
            try {
                int baseline = registeredResponses();
                var rpc = invoke(client);
                var source = rpc.thenApply(handle -> switch (carrier) {
                    case 1 -> (Object) ResponseEntity.ok(handle);
                    case 2 -> DubboResponses.ok(handle);
                    default -> handle;
                });
                var failure = new IllegalStateException("adapter failed");
                var observedFailure = new CompletableFuture<Throwable>();
                var adaptations = new AtomicInteger();
                var reply = DubboResponses.replyAsync(source, value -> {
                    adaptations.incrementAndGet();
                    assertEquals(baseline + 1, registeredResponses());
                    observedFailure.complete(failure);
                    throw failure;
                });
                try {
                    assertTrue(provider.connected.await(5, TimeUnit.SECONDS));
                    assertFalse(source.isDone());
                    if (cancel) assertTrue(reply.cancel(false));
                    assertFalse(rpc.isCancelled());
                    provider.allowResponse.countDown();
                    Object value = source.get(5, TimeUnit.SECONDS);
                    assertSame(failure, observedFailure.get(5, TimeUnit.SECONDS));
                    if (cancel) {
                        assertTrue(reply.isCancelled());
                    } else {
                        var error = assertThrows(CompletionException.class, reply::join);
                        assertSame(failure, error.getCause());
                    }
                    awaitRegisteredResponses(baseline);
                    assertEquals(1, adaptations.get(), "Adaptation and failure cleanup must run once");
                    if (carrier == 2) {
                        ((RawResponse) ((ResponseEntity<?>) value).getBody()).close();
                        assertEquals(baseline, registeredResponses(), "Repeated close must not release ownership twice");
                    }
                } finally {
                    provider.allowResponse.countDown();
                    rpc.whenComplete((handle, error) -> {
                        if (handle != null) NativeBridge.discardNativeResponse(handle.nativeId());
                    });
                }
            } finally {
                NativeDubboBridge.closeClient(client);
            }
        }
    }

    @Test
    void completedNativeReplyReleasesItsTransientBodyWhenHttpOwnerAborts() throws Exception {
        try (var provider = new JsonProvider()) {
            int client = createClient(provider);
            try {
                int baseline = registeredResponses();
                provider.allowResponse.countDown();
                var handle = invoke(client).get(5, TimeUnit.SECONDS);
                try {
                    assertTrue(handle.nativeId() > 0);
                    assertEquals(baseline + 1, registeredResponses());
                    var response = DubboResponses.reply(handle);
                    try (var body = response.getBody()) {
                        assertEquals(200, response.getStatus().getCode());
                        assertEquals(handle.nativeId(), body.getNativeId());
                        assertEquals(0, body.getBody().length);
                        assertEquals(baseline + 1, registeredResponses(), "Adapter must transfer, not discard, ownership");
                        body.close();
                        assertEquals(baseline, registeredResponses(), "HTTP abort retained the transient native response");
                        body.close();
                        assertEquals(baseline, registeredResponses(), "Repeated abort must be idempotent");
                    }
                } finally {
                    NativeBridge.discardNativeResponse(handle.nativeId());
                }
            } finally {
                NativeDubboBridge.closeClient(client);
            }
        }
    }

    @Test
    void cancelledHttpReplyStillAdaptsAndDiscardsTheLateJniResponse() throws Exception {
        try (var provider = new JsonProvider()) {
            int client = createClient(provider);
            try {
                int baseline = registeredResponses();
                var rpc = invoke(client);
                var adapted = new CompletableFuture<ResponseEntity<RawResponse>>();
                var countDuringAdaptation = new AtomicInteger(-1);
                var reply = DubboResponses.replyAsync(rpc, handle -> {
                    var response = DubboResponses.ok(handle);
                    countDuringAdaptation.set(registeredResponses());
                    adapted.complete(response);
                    return response;
                });
                try {
                    assertTrue(provider.connected.await(5, TimeUnit.SECONDS));
                    assertFalse(rpc.isDone(), "Provider cannot reply before the latch is released");
                    assertTrue(reply.cancel(false));
                    assertFalse(rpc.isCancelled(), "HTTP cancellation must not suppress the late native handle");
                    assertEquals(baseline, registeredResponses());
                    provider.allowResponse.countDown();

                    var handle = rpc.get(5, TimeUnit.SECONDS);
                    try (var body = adapted.get(5, TimeUnit.SECONDS).getBody()) {
                        assertTrue(handle.nativeId() > 0);
                        assertEquals(handle.nativeId(), body.getNativeId());
                        assertEquals(baseline + 1, countDuringAdaptation.get(), "Adapter must observe a real registered JNI response");
                        assertTrue(reply.isCancelled());
                        assertFalse(rpc.isCancelled());
                        // The adapter probe completes before replyAsync closes the rejected body.
                        awaitRegisteredResponses(baseline);
                    }
                    assertEquals(baseline, registeredResponses());
                } finally {
                    provider.allowResponse.countDown();
                    rpc.whenComplete((handle, error) -> {
                        if (handle != null) NativeBridge.discardNativeResponse(handle.nativeId());
                    });
                }
            } finally {
                NativeDubboBridge.closeClient(client);
            }
        }
    }

    private static int createClient(JsonProvider provider) {
        NativeDubboBridge.configureAsync(2, 16, "tokio-demux");
        int client = NativeDubboBridge.createClient(5000, 16, 1024, 1);
        try {
            NativeDubboBridge.updateProviders(client, "127.0.0.1:" + provider.server.getLocalPort());
            return client;
        } catch (RuntimeException | Error failure) {
            NativeDubboBridge.closeClient(client);
            throw failure;
        }
    }

    private static CompletableFuture<NativeResponseHandle> invoke(int client) {
        return NativeDubboBridge.invokeByteArrayNoArgsNativeJsonAsync(
                client, "sample.Service", null, null, "get", 5000);
    }

    private static int registeredResponses() {
        var match = RESPONSE_COUNT.matcher(NativeBridge.nativeMemoryDiagnosticsJson());
        assertTrue(match.find(), "Native diagnostics must contain the response registry count");
        return Integer.parseInt(match.group(1));
    }

    private static void awaitRegisteredResponses(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (registeredResponses() != expected && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(expected, registeredResponses(), "Cancelled HTTP reply retained the late native response");
    }

    private static final class JsonProvider implements AutoCloseable {
        private final ServerSocket server;
        private final ExecutorService peers = Executors.newSingleThreadExecutor();
        private final CountDownLatch connected = new CountDownLatch(1);
        private final CountDownLatch allowResponse = new CountDownLatch(1);
        private final Future<?> worker;
        private volatile Socket peer;

        private JsonProvider() throws IOException {
            server = new ServerSocket(0);
            server.setSoTimeout(5000);
            worker = peers.submit(() -> {
                try (var socket = server.accept()) {
                    peer = socket;
                    socket.setSoTimeout(5000);
                    connected.countDown();
                    assertTrue(allowResponse.await(5, TimeUnit.SECONDS), "Provider response latch was not released");
                    NativeDubboCallbackLifetimeTest.respondJson(socket);
                } catch (IOException closed) {
                    if (!server.isClosed()) throw closed;
                }
                return null;
            });
        }

        @Override
        public void close() throws Exception {
            allowResponse.countDown();
            server.close();
            Socket socket = peer;
            if (socket != null) socket.close();
            peers.shutdown();
            try {
                worker.get(6, TimeUnit.SECONDS);
            } finally {
                peers.shutdownNow();
                assertTrue(peers.awaitTermination(6, TimeUnit.SECONDS), "Fake provider worker did not stop");
            }
        }
    }
}
