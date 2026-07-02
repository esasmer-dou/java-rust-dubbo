package com.reactor.rust.dubbo;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

final class PendingNativeDubboInvocations {

    private final AtomicLong callbackIds = new AtomicLong(1);
    private final ConcurrentHashMap<Long, PendingInvoke> pending = new ConcurrentHashMap<>();

    PendingCall begin(int clientId) {
        long callbackId = callbackIds.getAndIncrement();
        CompletableFuture<byte[]> future = new CompletableFuture<>();
        pending.put(callbackId, new PendingInvoke(clientId, future));
        return new PendingCall(callbackId, future);
    }

    void accepted(PendingCall call, int timeoutMs) {
        long guardTimeoutMs = Math.max(1L, timeoutMs) + 1_000L;
        call.future()
                .orTimeout(guardTimeoutMs, TimeUnit.MILLISECONDS)
                .whenComplete((ignored, error) -> pending.remove(call.callbackId()));
    }

    void rejected(PendingCall call, String message) {
        if (pending.remove(call.callbackId()) != null) {
            call.future().completeExceptionally(new DubboConsumerException(message));
        }
    }

    void complete(long callbackId, byte[] responseBody, String errorMessage) {
        PendingInvoke invocation = pending.remove(callbackId);
        if (invocation == null) {
            return;
        }
        if (errorMessage == null) {
            invocation.future().complete(responseBody);
        } else {
            invocation.future().completeExceptionally(new DubboConsumerException(errorMessage));
        }
    }

    void closeClient(int clientId, RuntimeException error) {
        for (var entry : pending.entrySet()) {
            PendingInvoke invocation = entry.getValue();
            if (invocation.clientId() == clientId && pending.remove(entry.getKey(), invocation)) {
                invocation.future().completeExceptionally(error);
            }
        }
    }

    int size() {
        return pending.size();
    }

    record PendingCall(long callbackId, CompletableFuture<byte[]> future) {}

    private record PendingInvoke(int clientId, CompletableFuture<byte[]> future) {}
}
