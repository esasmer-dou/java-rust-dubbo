package com.reactor.rust.dubbo.provider;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;

/** Applies the service-wide limit and the optional per-method limit without per-call allocation. */
final class ProviderConcurrencyGate {

    private final String serviceName;
    private final int maxConcurrentInvocations;
    private final Semaphore serviceSemaphore;
    private final Map<String, MethodGate> methodGates;

    private ProviderConcurrencyGate(
            Class<?> serviceType,
            PlainDubboProvider.ServiceExecutionConfig executionConfig) {
        this.serviceName = serviceType.getName();
        this.maxConcurrentInvocations = executionConfig.maxConcurrentInvocations();
        this.serviceSemaphore = executionConfig.isBounded()
                ? new Semaphore(executionConfig.maxConcurrentInvocations(), false)
                : null;
        this.methodGates = methodGates(executionConfig.methodMaxConcurrentInvocations());
    }

    static ProviderConcurrencyGate forService(
            Class<?> serviceType,
            PlainDubboProvider.ServiceExecutionConfig executionConfig) {
        return new ProviderConcurrencyGate(serviceType, executionConfig);
    }

    MethodGate acquireOrReject(String methodName) {
        if (serviceSemaphore != null && !serviceSemaphore.tryAcquire()) {
            throw new RejectedExecutionException("Dubbo provider concurrency limit exceeded for "
                    + serviceName + "." + methodName
                    + " maxConcurrent=" + maxConcurrentInvocations);
        }

        MethodGate methodGate = methodGates.get(methodName);
        if (methodGate == null || methodGate.tryAcquire()) {
            return methodGate;
        }

        if (serviceSemaphore != null) {
            serviceSemaphore.release();
        }
        throw new RejectedExecutionException("Dubbo provider method concurrency limit exceeded for "
                + serviceName + "." + methodName
                + " maxConcurrent=" + methodGate.maxConcurrentInvocations);
    }

    void release(MethodGate methodGate) {
        if (methodGate != null) {
            methodGate.release();
        }
        if (serviceSemaphore != null) {
            serviceSemaphore.release();
        }
    }

    private static Map<String, MethodGate> methodGates(Map<String, Integer> methodLimits) {
        if (methodLimits.isEmpty()) {
            return Map.of();
        }
        Map<String, MethodGate> gates = new LinkedHashMap<>(methodLimits.size());
        methodLimits.forEach((methodName, limit) -> gates.put(methodName, new MethodGate(limit)));
        return Map.copyOf(gates);
    }

    static final class MethodGate {

        private final int maxConcurrentInvocations;
        private final Semaphore semaphore;

        private MethodGate(int maxConcurrentInvocations) {
            this.maxConcurrentInvocations = maxConcurrentInvocations;
            this.semaphore = new Semaphore(maxConcurrentInvocations, false);
        }

        private boolean tryAcquire() {
            return semaphore.tryAcquire();
        }

        private void release() {
            semaphore.release();
        }
    }
}
