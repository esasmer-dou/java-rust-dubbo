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
    void closeClientFailsOnlyMatchingClientCalls() {
        PendingNativeDubboInvocations pending = new PendingNativeDubboInvocations();
        PendingNativeDubboInvocations.PendingCall first = pending.begin(1);
        PendingNativeDubboInvocations.PendingCall second = pending.begin(2);

        pending.closeClient(1, new DubboConsumerException("closed"));

        ExecutionException error = assertThrows(ExecutionException.class, () -> first.future().get());
        assertInstanceOf(DubboConsumerException.class, error.getCause());
        assertEquals(1, pending.size());

        pending.complete(second.callbackId(), new byte[] {9}, null);
        assertEquals(0, pending.size());
    }
}
