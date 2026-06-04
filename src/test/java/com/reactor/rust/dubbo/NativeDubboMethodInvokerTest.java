package com.reactor.rust.dubbo;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NativeDubboMethodInvokerTest {

    @Test
    void lazyInvokerDoesNotCreateDelegateUntilFirstInvocation() {
        AtomicInteger created = new AtomicInteger();
        NativeDubboMethodInvoker<byte[]> invoker = NativeDubboMethodInvoker.lazy(() -> {
            created.incrementAndGet();
            throw new DubboConsumerException("expected");
        });

        assertEquals(0, created.get());

        assertThrows(DubboConsumerException.class, invoker::invoke);
        assertEquals(1, created.get());
    }
}
