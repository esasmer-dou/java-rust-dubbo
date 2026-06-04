package com.reactor.rust.dubbo;

import com.reactor.rust.dubbo.internal.nativeclient.NativeDubboReferenceHandle;
import com.reactor.rust.dubbo.internal.nativeclient.StaticNativeDubboReference;
import com.reactor.rust.dubbo.internal.runtime.DubboRuntimeTuning;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class NativeDubboConsumerClient implements AutoCloseable {

    private final DubboConsumerConfig config;
    private final AutoCloseable zookeeper;
    private final ThreadPoolExecutor refreshExecutor;
    private final ConcurrentHashMap<ReferenceKey, NativeDubboReferenceHandle<?>> references = new ConcurrentHashMap<>();
    private volatile boolean nativeAsyncConfigured;
    private volatile boolean closed;

    private NativeDubboConsumerClient(DubboConsumerConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        DubboRuntimeTuning.apply(this.config);
        if (config.staticProvidersEnabled()) {
            this.refreshExecutor = null;
            this.zookeeper = null;
        } else {
            this.refreshExecutor = createRefreshExecutor(config);
            this.zookeeper = DiscoverySupport.openRegistry(config, refreshExecutor);
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
            try {
                zookeeper.close();
            } catch (Exception e) {
                throw new DubboConsumerException("Failed to close native Dubbo registry client", e);
            }
        }
    }

    private <T> NativeDubboReferenceHandle<T> createReference(DubboReferenceSpec<T> spec) {
        try {
            configureNativeAsyncOnce();
            NativeDubboReferenceHandle<T> reference = config.staticProvidersEnabled()
                    ? new StaticNativeDubboReference<>(config, spec)
                    : DiscoverySupport.createReference(config, spec, zookeeper, refreshExecutor);
            reference.start();
            return reference;
        } catch (RuntimeException e) {
            throw new DubboConsumerException("Failed to create native Dubbo consumer reference for "
                    + spec.serviceInterface().getName(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> NativeDubboReferenceHandle<T> reference(DubboReferenceSpec<T> spec) {
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
                NativeDubboBridge.configureAsync(config.nativeAsyncWorkers(), config.nativeAsyncQueueCapacity());
                nativeAsyncConfigured = true;
            }
        }
    }

    private static ThreadPoolExecutor createRefreshExecutor(DubboConsumerConfig config) {
        int threads = Math.max(1, config.referThreadNum());
        int queueCapacity = DubboConsumerConfig.RUNTIME_PROFILE_MICRO_DUBBO.equals(config.runtimeProfile()) ? 8 : 64;
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                threads,
                threads,
                30L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                daemonThreadFactory("reactor-native-dubbo-zk-refresh"),
                new ThreadPoolExecutor.CallerRunsPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    private static ThreadFactory daemonThreadFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger(1);
        return task -> {
            Thread thread = new Thread(task, prefix + "-" + sequence.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
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

        private static AutoCloseable openRegistry(DubboConsumerConfig config, Executor refreshExecutor) {
            try {
                Method method = Class.forName(FACTORY_CLASS)
                        .getMethod(OPEN_REGISTRY, DubboConsumerConfig.class, Executor.class);
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
                Executor refreshExecutor
        ) {
            try {
                Method method = Class.forName(FACTORY_CLASS)
                        .getMethod(CREATE_REFERENCE,
                                DubboConsumerConfig.class,
                                DubboReferenceSpec.class,
                                AutoCloseable.class,
                                Executor.class);
                return (NativeDubboReferenceHandle<T>) method.invoke(null, config, spec, zookeeper, refreshExecutor);
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
