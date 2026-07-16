package com.reactor.rust.dubbo;

import com.reactor.rust.dubbo.internal.nativeclient.NativeDubboReferenceHandle;
import com.reactor.rust.dubbo.internal.nativeclient.StaticNativeDubboReference;
import com.reactor.rust.dubbo.internal.nativeclient.LegacyDecodeExecutor;
import com.reactor.rust.dubbo.internal.runtime.DubboRuntimeTuning;
import com.reactor.rust.dubbo.internal.util.RegistryExecutors;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;

public final class NativeDubboConsumerClient implements AutoCloseable {

    private final DubboConsumerConfig config;
    private final AutoCloseable zookeeper;
    private final ScheduledThreadPoolExecutor refreshExecutor;
    private final LegacyDecodeExecutor legacyDecodeExecutor;
    private final ConcurrentHashMap<ReferenceKey, NativeDubboReferenceHandle<?>> references = new ConcurrentHashMap<>();
    private volatile boolean nativeAsyncConfigured;
    private volatile boolean closed;

    private NativeDubboConsumerClient(DubboConsumerConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        DubboRuntimeTuning.apply(this.config);
        this.legacyDecodeExecutor = LegacyDecodeExecutor.forConfig(this.config);
        if (config.staticProvidersEnabled()) {
            this.refreshExecutor = null;
            this.zookeeper = null;
        } else {
            ScheduledThreadPoolExecutor executor = createRefreshExecutor(config);
            AutoCloseable registry;
            try {
                registry = DiscoverySupport.openRegistry(config, executor);
            } catch (RuntimeException | LinkageError startupFailure) {
                executor.shutdownNow();
                legacyDecodeExecutor.close();
                throw startupFailure;
            }
            this.refreshExecutor = executor;
            this.zookeeper = registry;
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
        if (lazy(spec)) {
            return NativeDubboMethodInvoker.lazy(() -> reference(spec).methodInvoker(methodName, returnType, parameterTypes));
        }
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
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        Throwable failure = null;
        for (NativeDubboReferenceHandle<?> reference : references.values()) {
            try {
                reference.close();
            } catch (RuntimeException | LinkageError closeFailure) {
                failure = CloseFailures.add(failure, closeFailure);
            }
        }
        references.clear();
        if (zookeeper != null) {
            try {
                zookeeper.close();
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                failure = CloseFailures.add(failure, e);
            }
        }
        if (refreshExecutor != null) {
            refreshExecutor.shutdownNow();
        }
        legacyDecodeExecutor.close();
        CloseFailures.rethrow(failure, "Failed to close native Dubbo consumer resources");
    }

    private <T> NativeDubboReferenceHandle<T> createReference(DubboReferenceSpec<T> spec) {
        try {
            configureNativeAsyncOnce();
            NativeDubboReferenceHandle<T> reference = config.staticProvidersEnabled()
                    ? new StaticNativeDubboReference<>(config, spec, legacyDecodeExecutor)
                    : DiscoverySupport.createReference(
                            config, spec, zookeeper, refreshExecutor, legacyDecodeExecutor);
            try {
                reference.start();
                return reference;
            } catch (RuntimeException | LinkageError failure) {
                closeAfterFailedStart(reference, failure);
                throw failure;
            }
        } catch (RuntimeException e) {
            throw new DubboConsumerException("Failed to create native Dubbo consumer reference for "
                    + spec.serviceInterface().getName(), e);
        }
    }

    private static void closeAfterFailedStart(
            NativeDubboReferenceHandle<?> reference,
            Throwable startupFailure) {
        try {
            reference.close();
        } catch (RuntimeException | LinkageError closeFailure) {
            startupFailure.addSuppressed(closeFailure);
        }
    }

    @SuppressWarnings("unchecked")
    private synchronized <T> NativeDubboReferenceHandle<T> reference(DubboReferenceSpec<T> spec) {
        ensureOpen();
        ReferenceKey key = ReferenceKey.from(spec, config);
        return (NativeDubboReferenceHandle<T>) references.computeIfAbsent(key, ignored -> createReference(spec));
    }

    private void ensureOpen() {
        if (closed) {
            throw new DubboConsumerException("NativeDubboConsumerClient is closed");
        }
    }

    private boolean lazy(DubboReferenceSpec<?> spec) {
        Boolean referenceLazy = spec.lazy();
        return referenceLazy == null ? config.lazy() : referenceLazy;
    }

    private void configureNativeAsyncOnce() {
        if (nativeAsyncConfigured) {
            return;
        }
        synchronized (this) {
            if (!nativeAsyncConfigured) {
                NativeDubboBridge.configureAsync(
                        config.nativeAsyncWorkers(),
                        config.nativeAsyncQueueCapacity(),
                        config.nativeAsyncTransport(),
                        config.nativeThreadStackBytes());
                nativeAsyncConfigured = true;
            }
        }
    }

    private static ScheduledThreadPoolExecutor createRefreshExecutor(DubboConsumerConfig config) {
        return RegistryExecutors.create(config, "reactor-native-dubbo-zk-refresh");
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

    private static final class DiscoverySupport {
        private static final String FACTORY_CLASS =
                "com.reactor.rust.dubbo.internal.nativeclient.ZookeeperNativeDubboReferenceFactory";
        private static final String OPEN_REGISTRY = "openRegistry";
        private static final String CREATE_REFERENCE = "createReference";

        private DiscoverySupport() {
        }

        private static AutoCloseable openRegistry(
                DubboConsumerConfig config,
                ScheduledExecutorService refreshExecutor) {
            try {
                Method method = Class.forName(FACTORY_CLASS)
                        .getMethod(OPEN_REGISTRY, DubboConsumerConfig.class, ScheduledExecutorService.class);
                return (AutoCloseable) method.invoke(null, config, refreshExecutor);
            } catch (ClassNotFoundException e) {
                throw new DubboConsumerException("ZooKeeper discovery requires optional ZooKeeper classes. "
                        + "Use reactor.dubbo.providers for static-provider micro-dubbo mode or add ZooKeeper dependencies.", e);
            } catch (NoSuchMethodException | IllegalAccessException e) {
                throw new DubboConsumerException("Invalid ZooKeeper discovery adapter wiring", e);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new DubboConsumerException("Failed to open ZooKeeper discovery adapter", cause);
            }
        }

        @SuppressWarnings("unchecked")
        private static <T> NativeDubboReferenceHandle<T> createReference(
                DubboConsumerConfig config,
                DubboReferenceSpec<T> spec,
                AutoCloseable zookeeper,
                Executor refreshExecutor,
                Executor legacyDecodeExecutor
        ) {
            try {
                Method method = Class.forName(FACTORY_CLASS)
                        .getMethod(CREATE_REFERENCE,
                                DubboConsumerConfig.class,
                                DubboReferenceSpec.class,
                                AutoCloseable.class,
                                Executor.class,
                                Executor.class);
                return (NativeDubboReferenceHandle<T>) method.invoke(
                        null, config, spec, zookeeper, refreshExecutor, legacyDecodeExecutor);
            } catch (ClassNotFoundException e) {
                throw new DubboConsumerException("ZooKeeper discovery requires optional ZooKeeper classes. "
                        + "Use reactor.dubbo.providers for static-provider micro-dubbo mode or add ZooKeeper dependencies.", e);
            } catch (NoSuchMethodException | IllegalAccessException e) {
                throw new DubboConsumerException("Invalid ZooKeeper discovery adapter wiring", e);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new DubboConsumerException("Failed to create ZooKeeper native Dubbo reference", cause);
            }
        }
    }
}
