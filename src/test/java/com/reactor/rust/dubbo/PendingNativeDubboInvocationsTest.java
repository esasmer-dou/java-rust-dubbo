package com.reactor.rust.dubbo;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PendingNativeDubboInvocationsTest {

    @Test void nativeResponseOwnershipIsRejectedAfterCancellationExpiryAndClientClose() {
        var pending = new PendingNativeDubboInvocations();
        var cancelled = pending.beginNativeResponse(1);
        cancelled.future().cancel(false);
        org.junit.jupiter.api.Assertions.assertFalse(pending.completeNativeResponse(
                cancelled.callbackId(), 40, 200, "application/json", "", null));
        var expired = pending.beginNativeResponse(1);
        pending.accepted(expired, 1);
        pending.expirePending(System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2));
        org.junit.jupiter.api.Assertions.assertFalse(pending.completeNativeResponse(
                expired.callbackId(), 41, 200, "application/json", "", null));
        var closed = pending.beginNativeResponse(2);
        pending.closeClient(2, new DubboConsumerException("closed"));
        org.junit.jupiter.api.Assertions.assertFalse(pending.completeNativeResponse(
                closed.callbackId(), 42, 200, "application/json", "", null));
        assertEquals(0, pending.size());
    }

    @Test
    void oneGuardCoversManyCallsAndNeverExpiresBeforeDeadline() {
        var pending = new PendingNativeDubboInvocations();
        var calls = new java.util.ArrayList<PendingNativeDubboInvocations.PendingCall>();
        long before = System.nanoTime();
        for (int i = 0; i < 128; i++) {
            var call = pending.begin(7);
            pending.accepted(call, 60_000);
            calls.add(call);
        }
        pending.expirePending(before);
        assertEquals(128, pending.size());
        org.junit.jupiter.api.Assertions.assertTrue(pending.guardTasksForTest() < 128);
        pending.expirePending(System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(62));
        assertEquals(0, pending.size());
        for (var call : calls) {
            ExecutionException error = assertThrows(ExecutionException.class, () -> call.future().get());
            assertInstanceOf(java.util.concurrent.TimeoutException.class, error.getCause());
        }
    }

    @Test
    void cancellationAndExternalCompletionAreCollectedWithoutCallback() {
        var pending = new PendingNativeDubboInvocations();
        var cancelled = pending.begin(1);
        var externallyCompleted = pending.begin(2);
        pending.accepted(cancelled, 60_000);
        pending.accepted(externallyCompleted, 60_000);
        cancelled.future().cancel(false);
        externallyCompleted.future().complete(new byte[]{3});
        pending.expirePending(System.nanoTime());
        assertEquals(0, pending.size());
    }

    @Test
    void completionBeforeAcceptanceNeedsNoGuardTask() {
        var pending = new PendingNativeDubboInvocations();
        var call = pending.begin(1);
        pending.complete(call.callbackId(), new byte[]{9}, null);
        pending.accepted(call, 1000);
        assertEquals(0, pending.size());
        assertEquals(0, pending.guardTasksForTest());
    }

    @Test
    void completeRemovesPendingAndCompletesFuture() throws Exception {
        PendingNativeDubboInvocations pending = new PendingNativeDubboInvocations();
        PendingNativeDubboInvocations.PendingCall call = pending.begin(7);

        pending.complete(call.callbackId(), new byte[] {1, 2, 3}, null);

        assertArrayEquals(new byte[] {1, 2, 3}, call.future().get());
        assertEquals(0, pending.size());
    }

    @Test
    void rejectedRemovesPendingAndFailsFuture() {
        PendingNativeDubboInvocations pending = new PendingNativeDubboInvocations();
        PendingNativeDubboInvocations.PendingCall call = pending.begin(7);

        pending.rejected(call, "queue rejected");

        ExecutionException error = assertThrows(ExecutionException.class, () -> call.future().get());
        assertInstanceOf(DubboConsumerException.class, error.getCause());
        assertEquals(0, pending.size());
    }

    @Test
    void rejectedWithOriginalFailureRemovesPendingAndPreservesCause() {
        PendingNativeDubboInvocations pending = new PendingNativeDubboInvocations();
        PendingNativeDubboInvocations.PendingCall call = pending.begin(7);
        IllegalStateException failure = new IllegalStateException("native submission failed");

        pending.rejected(call, failure);

        ExecutionException error = assertThrows(ExecutionException.class, () -> call.future().get());
        assertEquals(failure, error.getCause());
        assertEquals(0, pending.size());
    }

    @Test
    void completeNativeResponseRemovesPendingAndCompletesFuture() throws Exception {
        PendingNativeDubboInvocations pending = new PendingNativeDubboInvocations();
        PendingNativeDubboInvocations.PendingNativeResponseCall call = pending.beginNativeResponse(7);

        pending.completeNativeResponse(
                call.callbackId(),
                42,
                201,
                "application/json; charset=utf-8",
                "",
                null);

        NativeResponseHandle handle = call.future().get();
        assertEquals(42, handle.nativeId());
        assertEquals(201, handle.statusCode());
        assertEquals("application/json; charset=utf-8", handle.contentType());
        assertEquals(0, pending.size());
    }

    @Test
    void invalidNativeResponseHandleFailsFuture() {
        PendingNativeDubboInvocations pending = new PendingNativeDubboInvocations();
        PendingNativeDubboInvocations.PendingNativeResponseCall call = pending.beginNativeResponse(7);

        pending.completeNativeResponse(call.callbackId(), 0, 0, "", "", null);

        ExecutionException error = assertThrows(ExecutionException.class, () -> call.future().get());
        assertInstanceOf(DubboConsumerException.class, error.getCause());
        assertEquals(0, pending.size());
    }

    @Test
    void closeClientFailsOnlyMatchingClientCalls() {
        PendingNativeDubboInvocations pending = new PendingNativeDubboInvocations();
        PendingNativeDubboInvocations.PendingCall first = pending.begin(1);
        PendingNativeDubboInvocations.PendingNativeResponseCall second = pending.beginNativeResponse(2);

        pending.closeClient(1, new DubboConsumerException("closed"));

        ExecutionException error = assertThrows(ExecutionException.class, () -> first.future().get());
        assertInstanceOf(DubboConsumerException.class, error.getCause());
        assertEquals(1, pending.size());

        pending.completeNativeResponse(second.callbackId(), 9, 200, "application/json; charset=utf-8", "", null);
        assertEquals(0, pending.size());
    }
}
