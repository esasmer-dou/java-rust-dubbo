package com.reactor.rust.dubbo;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

final class PendingNativeDubboInvocations {

    private final AtomicLong callbackIds = new AtomicLong(1);
    private final ConcurrentHashMap<Long, PendingInvoke<?>> pending = new ConcurrentHashMap<>();

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
        long guardTimeoutMs = Math.max(1L, timeoutMs) + 1_000L;
        future
                .orTimeout(guardTimeoutMs, TimeUnit.MILLISECONDS)
                .whenComplete((ignored, error) -> pending.remove(callbackId));
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
    void completeNativeResponse(
            long callbackId,
            int nativeResponseId,
            int statusCode,
            String contentType,
            String headers,
            String errorMessage) {
        PendingInvoke<?> invocation = pending.remove(callbackId);
        if (invocation == null) {
            return;
        }
        if (errorMessage == null && nativeResponseId > 0) {
            ((CompletableFuture<NativeResponseHandle>) invocation.future()).complete(
                    new NativeResponseHandle(nativeResponseId, statusCode, contentType, headers));
        } else if (errorMessage == null) {
            invocation.future().completeExceptionally(new DubboConsumerException(
                    "Native Dubbo returned an invalid HTTP response handle"));
        } else {
            invocation.future().completeExceptionally(new DubboConsumerException(errorMessage));
        }
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

    private record PendingInvoke<T>(int clientId, CompletableFuture<T> future) {}
}
