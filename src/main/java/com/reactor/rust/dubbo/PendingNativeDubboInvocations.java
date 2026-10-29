package com.reactor.rust.dubbo;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

final class PendingNativeDubboInvocations {

    private final AtomicLong callbackIds = new AtomicLong(1);
    private final ConcurrentHashMap<Long, PendingInvoke<?>> pending = new ConcurrentHashMap<>();
    private final AtomicBoolean guardActive = new AtomicBoolean();
    private final AtomicLong guardTasks = new AtomicLong();
    private final BiConsumer<Void, Throwable> guardAction = (ignored, error) -> {
        try {
            expirePending(System.nanoTime());
        } finally {
            guardActive.set(false);
            if (!pending.isEmpty()) startGuard();
        }
    };

    PendingCall begin(int clientId) {
        long callbackId = nextCallbackId();
        CompletableFuture<byte[]> future = new CompletableFuture<>();
        pending.put(callbackId, new PendingInvoke<>(clientId, future));
        return new PendingCall(callbackId, future);
    }

    PendingNativeResponseCall beginNativeResponse(int clientId) {
        long callbackId = nextCallbackId();
        CompletableFuture<NativeResponseHandle> future = new CompletableFuture<>();
        pending.put(callbackId, new PendingInvoke<>(clientId, future));
        return new PendingNativeResponseCall(callbackId, future);
    }

    void accepted(PendingCall call, int timeoutMs) {
        accepted(call.callbackId(), call.future(), timeoutMs);
    }

    void accepted(PendingNativeResponseCall call, int timeoutMs) {
        accepted(call.callbackId(), call.future(), timeoutMs);
    }

    private void accepted(long callbackId, CompletableFuture<?> future, int timeoutMs) {
        PendingInvoke<?> invocation = pending.get(callbackId);
        if (invocation == null) return; // A fast native completion already removed it.
        long guardTimeoutMs = Math.max(1L, timeoutMs) + 1_000L;
        invocation.guardDeadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(guardTimeoutMs);
        if (future.isDone()) pending.remove(callbackId, invocation);
        else startGuard();
    }

    // Rust owns the RPC deadline. This is the existing +1s lost-callback guard,
    // shared across active calls instead of allocating/cancelling a timer per RPC.
    private void startGuard() {
        if (guardActive.compareAndSet(false, true)) {
            guardTasks.incrementAndGet();
            new CompletableFuture<Void>()
                    .completeOnTimeout(null, 50, TimeUnit.MILLISECONDS)
                    .whenComplete(guardAction);
        }
    }

    void primeGuard() {
        startGuard();
    }

    void expirePending(long nowNanos) {
        for (var entry : pending.entrySet()) {
            PendingInvoke<?> invocation = entry.getValue();
            if (invocation.future().isDone()) {
                pending.remove(entry.getKey(), invocation);
            } else if (invocation.guardDeadlineNanos != 0
                    && nowNanos - invocation.guardDeadlineNanos >= 0
                    && pending.remove(entry.getKey(), invocation)) {
                invocation.future().completeExceptionally(new TimeoutException());
            }
        }
    }

    long guardTasksForTest() {
        return guardTasks.get();
    }

    void rejected(PendingCall call, String message) {
        rejected(call.callbackId(), call.future(), message);
    }

    void rejected(PendingNativeResponseCall call, String message) {
        rejected(call.callbackId(), call.future(), message);
    }

    void rejected(PendingCall call, Throwable error) {
        rejected(call.callbackId(), call.future(), error);
    }

    void rejected(PendingNativeResponseCall call, Throwable error) {
        rejected(call.callbackId(), call.future(), error);
    }

    private void rejected(long callbackId, CompletableFuture<?> future, String message) {
        if (pending.remove(callbackId) != null) {
            future.completeExceptionally(new DubboConsumerException(message));
        }
    }

    private void rejected(long callbackId, CompletableFuture<?> future, Throwable error) {
        if (pending.remove(callbackId) != null) {
            RuntimeException failure = error instanceof RuntimeException runtime
                    ? runtime
                    : new DubboConsumerException("Native Dubbo async invocation failed", error);
            future.completeExceptionally(failure);
        }
    }

    @SuppressWarnings("unchecked")
    void complete(long callbackId, byte[] responseBody, String errorMessage) {
        PendingInvoke<?> invocation = pending.remove(callbackId);
        if (invocation == null) {
            return;
        }
        if (errorMessage == null) {
            ((CompletableFuture<byte[]>) invocation.future()).complete(responseBody);
        } else {
            invocation.future().completeExceptionally(new DubboConsumerException(errorMessage));
        }
    }

    @SuppressWarnings("unchecked")
    boolean completeNativeResponse(
            long callbackId,
            int nativeResponseId,
            int statusCode,
            String contentType,
            String headers,
            String errorMessage) {
        PendingInvoke<?> invocation = pending.remove(callbackId);
        if (invocation == null) {
            return false;
        }
        if (errorMessage == null && nativeResponseId > 0) {
            return ((CompletableFuture<NativeResponseHandle>) invocation.future()).complete(
                    new NativeResponseHandle(nativeResponseId, statusCode, contentType, headers));
        } else if (errorMessage == null) {
            invocation.future().completeExceptionally(new DubboConsumerException(
                    "Native Dubbo returned an invalid HTTP response handle"));
        } else {
            invocation.future().completeExceptionally(new DubboConsumerException(errorMessage));
        }
        return false;
    }

    void closeClient(int clientId, RuntimeException error) {
        for (var entry : pending.entrySet()) {
            PendingInvoke<?> invocation = entry.getValue();
            if (invocation.clientId() == clientId && pending.remove(entry.getKey(), invocation)) {
                invocation.future().completeExceptionally(error);
            }
        }
    }

    int size() {
        return pending.size();
    }

    private long nextCallbackId() {
        long callbackId = callbackIds.getAndIncrement();
        if (callbackId > 0) {
            return callbackId;
        }
        throw new IllegalStateException("Native Dubbo callback id space exhausted");
    }

    record PendingCall(long callbackId, CompletableFuture<byte[]> future) {}

    record PendingNativeResponseCall(long callbackId, CompletableFuture<NativeResponseHandle> future) {}

    private static final class PendingInvoke<T> {
        private final int clientId;
        private final CompletableFuture<T> future;
        private volatile long guardDeadlineNanos;

        private PendingInvoke(int clientId, CompletableFuture<T> future) {
            this.clientId = clientId;
            this.future = future;
        }

        int clientId() { return clientId; }
        CompletableFuture<T> future() { return future; }
    }
}
