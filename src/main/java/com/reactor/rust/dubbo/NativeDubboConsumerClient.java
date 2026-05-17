package com.reactor.rust.dubbo;

import com.reactor.rust.dubbo.internal.nativeclient.NativeDubboReference;
import com.reactor.rust.dubbo.internal.registry.ZookeeperRegistryClient;
import com.reactor.rust.dubbo.internal.util.NamedDaemonThreadFactory;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class NativeDubboConsumerClient implements AutoCloseable {

    private final DubboConsumerConfig config;
    private final Object zookeeper;
    private final ThreadPoolExecutor refreshExecutor;
    private final ConcurrentHashMap<ReferenceKey, NativeDubboReference<?>> references = new ConcurrentHashMap<>();
    private volatile boolean closed;

    private NativeDubboConsumerClient(DubboConsumerConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        NativeDubboBridge.configureAsync(config.nativeAsyncWorkers(), config.nativeAsyncQueueCapacity());
        if (config.staticProvidersEnabled()) {
            this.refreshExecutor = null;
            this.zookeeper = null;
        } else {
            this.refreshExecutor = createRefreshExecutor(config);
            this.zookeeper = new ZookeeperRegistryClient(config, refreshExecutor);
            ((ZookeeperRegistryClient) this.zookeeper).start();
        }
    }

    public static NativeDubboConsumerClient create(DubboConsumerConfig config) {
        return new NativeDubboConsumerClient(config);
    }

    public <T> T get(Class<T> serviceInterface) {
        return get(DubboReferenceSpec.of(serviceInterface));
    }

    public <T> T get(DubboReferenceSpec<T> spec) {
        Objects.requireNonNull(spec, "spec");
        ensureOpen();
        return spec.serviceInterface().cast(reference(spec).proxy());
    }

    public <T, R> NativeDubboMethodInvoker<R> method(
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

    public int referenceCount() {
        return references.size();
    }

    public String nativeMetricsJson() {
        return NativeDubboBridge.metricsJson();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        references.forEach((ignored, reference) -> reference.close());
        references.clear();
        if (refreshExecutor != null) {
            refreshExecutor.shutdownNow();
        }
        if (zookeeper != null) {
            ((ZookeeperRegistryClient) zookeeper).close();
        }
    }

    private <T> NativeDubboReference<T> createReference(DubboReferenceSpec<T> spec) {
        try {
            NativeDubboReference<T> reference = config.staticProvidersEnabled()
                    ? new NativeDubboReference<>(config, spec)
                    : new NativeDubboReference<>(config, spec, zookeeper, refreshExecutor);
            reference.start();
            return reference;
        } catch (RuntimeException e) {
            throw new DubboConsumerException("Failed to create native Dubbo consumer reference for "
                    + spec.serviceInterface().getName(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> NativeDubboReference<T> reference(DubboReferenceSpec<T> spec) {
        ReferenceKey key = ReferenceKey.from(spec, config);
        return (NativeDubboReference<T>) references.computeIfAbsent(key, ignored -> createReference(spec));
    }

    private void ensureOpen() {
        if (closed) {
            throw new DubboConsumerException("NativeDubboConsumerClient is closed");
        }
    }

    private static ThreadPoolExecutor createRefreshExecutor(DubboConsumerConfig config) {
        int threads = Math.max(1, config.referThreadNum());
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                threads,
                threads,
                30L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(64),
                new NamedDaemonThreadFactory("reactor-native-dubbo-zk-refresh"),
                new ThreadPoolExecutor.CallerRunsPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    private record ReferenceKey(
            String interfaceName,
            String group,
            String version,
            String protocol,
            String serialization,
            Integer timeoutMs,
            Boolean check,
            Integer connections) {

        static ReferenceKey from(DubboReferenceSpec<?> spec, DubboConsumerConfig config) {
            return new ReferenceKey(
                    spec.serviceInterface().getName(),
                    spec.group(),
                    spec.version(),
                    valueOrDefault(spec.protocol(), config.protocol()),
                    valueOrDefault(spec.serialization(), config.serialization()),
                    valueOrDefault(spec.timeoutMs(), config.timeoutMs()),
                    valueOrDefault(spec.check(), config.check()),
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
