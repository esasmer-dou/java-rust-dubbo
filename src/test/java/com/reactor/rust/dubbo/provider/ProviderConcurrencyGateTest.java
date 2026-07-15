package com.reactor.rust.dubbo.provider;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProviderConcurrencyGateTest {

    @Test
    void methodLimitDoesNotBypassServiceWideLimit() {
        ProviderConcurrencyGate gate = ProviderConcurrencyGate.forService(
                TestService.class,
                PlainDubboProvider.ServiceExecutionConfig.bounded(1, Map.of("read", 1)));

        ProviderConcurrencyGate.MethodGate readPermit = gate.acquireOrReject("read");
        assertThrows(RejectedExecutionException.class, () -> gate.acquireOrReject("write"));
        gate.release(readPermit);

        ProviderConcurrencyGate.MethodGate writePermit = gate.acquireOrReject("write");
        gate.release(writePermit);
    }

    @Test
    void rejectedMethodLimitReturnsServicePermit() {
        ProviderConcurrencyGate gate = ProviderConcurrencyGate.forService(
                TestService.class,
                PlainDubboProvider.ServiceExecutionConfig.bounded(2, Map.of("read", 1)));

        ProviderConcurrencyGate.MethodGate firstRead = gate.acquireOrReject("read");
        assertThrows(RejectedExecutionException.class, () -> gate.acquireOrReject("read"));

        ProviderConcurrencyGate.MethodGate writePermit = assertDoesNotThrow(() -> gate.acquireOrReject("write"));
        gate.release(writePermit);
        gate.release(firstRead);
    }

    @Test
    void unboundedGateIsAllocationFreeNoop() {
        ProviderConcurrencyGate gate = ProviderConcurrencyGate.forService(
                TestService.class,
                PlainDubboProvider.ServiceExecutionConfig.unbounded());

        ProviderConcurrencyGate.MethodGate permit = gate.acquireOrReject("read");
        gate.release(permit);
    }

    private interface TestService {
        void read();

        void write();
    }
}
