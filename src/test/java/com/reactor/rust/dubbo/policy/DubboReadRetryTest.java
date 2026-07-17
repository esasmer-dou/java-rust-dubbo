package com.reactor.rust.dubbo.policy;

import com.reactor.rust.dubbo.DubboConsumerException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DubboReadRetryTest {

    @Test
    void retriesOnlyConnectionAbortOnce() {
        AtomicInteger attempts = new AtomicInteger();

        String result = DubboReadRetry.onceOnConnectionAbort(true, () -> {
            if (attempts.incrementAndGet() == 1) {
                return CompletableFuture.failedFuture(new DubboConsumerException(
                        "Dubbo provider I/O error: connection reset by peer"));
            }
            return CompletableFuture.completedFuture("ok");
        }).join();

        assertEquals("ok", result);
        assertEquals(2, attempts.get());
    }

    @Test
    void doesNotRetryProtocolFailure() {
        AtomicInteger attempts = new AtomicInteger();

        CompletableFuture<String> result = DubboReadRetry.onceOnConnectionAbort(true, () -> {
            attempts.incrementAndGet();
            return CompletableFuture.failedFuture(new DubboConsumerException("provider returned status 40"));
        });

        assertTrue(result.isCompletedExceptionally());
        assertEquals(1, attempts.get());
    }
}
