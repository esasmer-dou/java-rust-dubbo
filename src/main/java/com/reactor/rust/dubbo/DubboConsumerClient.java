package com.reactor.rust.dubbo;

import com.reactor.rust.dubbo.internal.direct.DirectDubboReference;
import com.reactor.rust.dubbo.internal.registry.ZookeeperRegistryClient;
import com.reactor.rust.dubbo.internal.runtime.DubboRuntimeTuning;
import com.reactor.rust.dubbo.internal.util.RegistryExecutors;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;

public final class DubboConsumerClient implements AutoCloseable {

    private final DubboConsumerConfig config;
    private final ZookeeperRegistryClient zookeeper;
    private final ScheduledThreadPoolExecutor refreshExecutor;
    private final ConcurrentHashMap<ReferenceKey, DirectDubboReference<?>> references = new ConcurrentHashMap<>();
    private final DubboConsumerMetrics metrics = new DubboConsumerMetrics();
    private volatile boolean closed;

    private DubboConsumerClient(DubboConsumerConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        DubboRuntimeTuning.apply(this.config);
        ScheduledThreadPoolExecutor executor = createRefreshExecutor(config);
        ZookeeperRegistryClient registry = new ZookeeperRegistryClient(config, executor);
        try {
            registry.start();
        } catch (RuntimeException | LinkageError startupFailure) {
            try {
                registry.close();
            } catch (RuntimeException | LinkageError closeFailure) {
                startupFailure.addSuppressed(closeFailure);
            } finally {
                executor.shutdownNow();
            }
            throw startupFailure;
        }
        this.refreshExecutor = executor;
        this.zookeeper = registry;
    }

    public static DubboConsumerClient create(DubboConsumerConfig config) {
        return new DubboConsumerClient(config);
    }

    public static DubboConsumerClient createFromProperties() {
        return create(DubboConsumerConfig.fromProperties());
    }

    public <T> T get(Class<T> serviceInterface) {
        return get(DubboReferenceSpec.of(serviceInterface));
    }

    public <T> T get(DubboReferenceSpec<T> spec) {
        Objects.requireNonNull(spec, "spec");
        ensureOpen();
        DirectDubboReference<?> reference = reference(spec);
        return spec.serviceInterface().cast(reference.proxy());
    }

    public <T, R> DubboMethodInvoker<R> method(
            Class<T> serviceInterface,
            String methodName,
            Class<R> returnType,
            Class<?>... parameterTypes) {
        return method(DubboReferenceSpec.of(serviceInterface), methodName, returnType, parameterTypes);
    }

    public <T, R> DubboMethodInvoker<R> method(
            DubboReferenceSpec<T> spec,
            String methodName,
            Class<R> returnType,
            Class<?>... parameterTypes) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(methodName, "methodName");
        Objects.requireNonNull(returnType, "returnType");
        ensureOpen();
        return reference(spec).methodInvoker(methodName, returnType, parameterTypes);
    }

    public DubboConsumerConfig config() {
        return config;
    }

    public DubboConsumerMetrics metrics() {
        return metrics;
    }

    public int referenceCount() {
        return references.size();
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        Throwable failure = null;
        for (DirectDubboReference<?> reference : references.values()) {
            try {
                reference.close();
            } catch (RuntimeException | LinkageError closeFailure) {
                failure = CloseFailures.add(failure, closeFailure);
            }
        }
        references.clear();
        try {
            zookeeper.close();
        } catch (RuntimeException | LinkageError closeFailure) {
            failure = CloseFailures.add(failure, closeFailure);
        } finally {
            refreshExecutor.shutdownNow();
            metrics.recordClose();
        }
        CloseFailures.rethrow(failure, "Failed to close Dubbo consumer resources");
    }

    void recordFieldInjected() {
        metrics.recordFieldInjected();
    }

    private <T> DirectDubboReference<T> createReference(DubboReferenceSpec<T> spec) {
        try {
            DirectDubboReference<T> reference = new DirectDubboReference<>(config, spec, zookeeper, refreshExecutor);
            try {
                reference.start();
                metrics.recordReferenceCreated();
                return reference;
            } catch (RuntimeException | LinkageError failure) {
                closeAfterFailedStart(reference, failure);
                throw failure;
            }
        } catch (RuntimeException e) {
            metrics.recordReferenceCreateFailure();
            throw new DubboConsumerException("Failed to create Dubbo consumer reference for "
                    + spec.serviceInterface().getName(), e);
        }
    }

    private static void closeAfterFailedStart(
            DirectDubboReference<?> reference,
            Throwable startupFailure) {
        try {
            reference.close();
        } catch (RuntimeException | LinkageError closeFailure) {
            startupFailure.addSuppressed(closeFailure);
        }
    }

    @SuppressWarnings("unchecked")
    private synchronized <T> DirectDubboReference<T> reference(DubboReferenceSpec<T> spec) {
        ensureOpen();
        ReferenceKey key = ReferenceKey.from(spec, config);
        return (DirectDubboReference<T>) references.computeIfAbsent(key, ignored -> createReference(spec));
    }

    private void ensureOpen() {
        if (closed) {
            throw new DubboConsumerException("DubboConsumerClient is closed");
        }
    }

    private static ScheduledThreadPoolExecutor createRefreshExecutor(DubboConsumerConfig config) {
        return RegistryExecutors.create(config, "reactor-dubbo-zk-refresh");
    }

    private record ReferenceKey(
            String interfaceName,
            String group,
            String version,
            String protocol,
            String serialization,
            String cluster,
            String loadbalance,
            Integer timeoutMs,
            Integer retries,
            Boolean check,
            Boolean lazy,
            Integer connections) {

        static ReferenceKey from(DubboReferenceSpec<?> spec, DubboConsumerConfig config) {
            return new ReferenceKey(
                    spec.serviceInterface().getName(),
                    spec.group(),
                    spec.version(),
                    valueOrDefault(spec.protocol(), config.protocol()),
                    valueOrDefault(spec.serialization(), config.serialization()),
                    valueOrDefault(spec.cluster(), config.cluster()),
                    valueOrDefault(spec.loadbalance(), config.loadbalance()),
                    valueOrDefault(spec.timeoutMs(), config.timeoutMs()),
                    valueOrDefault(spec.retries(), config.retries()),
                    valueOrDefault(spec.check(), config.check()),
                    valueOrDefault(spec.lazy(), config.lazy()),
                    valueOrDefault(spec.connections(), config.connections()));
        }
    }

    private static int valueOrDefault(Integer value, int defaultValue) {
        return value == null ? defaultValue : value;
    }

    private static boolean valueOrDefault(Boolean value, boolean defaultValue) {
        return value == null ? defaultValue : value;
    }

    private static String valueOrDefault(String value, String defaultValue) {
        return value == null ? defaultValue : value;
    }
}
