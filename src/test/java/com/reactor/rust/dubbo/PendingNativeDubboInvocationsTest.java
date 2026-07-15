package com.reactor.rust.dubbo;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PendingNativeDubboInvocationsTest {

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
