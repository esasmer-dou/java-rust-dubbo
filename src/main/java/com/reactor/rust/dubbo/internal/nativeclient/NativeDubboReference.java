package com.reactor.rust.dubbo.internal.nativeclient;

import com.reactor.rust.dubbo.DubboConsumerConfig;
import com.reactor.rust.dubbo.DubboConsumerException;
import com.reactor.rust.dubbo.DubboReferenceSpec;
import com.reactor.rust.dubbo.NativeDubboBridge;
import com.reactor.rust.dubbo.NativeDubboMethodInvoker;
import com.reactor.rust.dubbo.internal.registry.ProviderWatcher;
import com.reactor.rust.dubbo.internal.registry.ZookeeperRegistryClient;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

final class NativeDubboReference<T> implements NativeDubboReferenceHandle<T> {

    private final DubboConsumerConfig config;
    private final DubboReferenceSpec<T> spec;
    private final Class<T> serviceInterface;
    private final int nativeClientId;
    private final ProviderWatcher watcher;
    private final Executor legacyDecodeExecutor;
    private volatile T proxy;
    private boolean closed;
    private static final Object[] EMPTY_ARGS = new Object[0];

    NativeDubboReference(
            DubboConsumerConfig config,
            DubboReferenceSpec<T> spec,
            ZookeeperRegistryClient zookeeper,
            Executor refreshExecutor,
            Executor legacyDecodeExecutor) {
        this.config = Objects.requireNonNull(config, "config");
        this.spec = Objects.requireNonNull(spec, "spec");
        this.serviceInterface = spec.serviceInterface();
        this.legacyDecodeExecutor = Objects.requireNonNull(legacyDecodeExecutor, "legacyDecodeExecutor");
        this.nativeClientId = createNativeClient(config, spec);
        try {
            this.watcher = new NativeDubboProviderWatcher<>(
                    config,
                    spec,
                    zookeeper,
                    refreshExecutor,
                    nativeClientId);
        } catch (RuntimeException | LinkageError constructionFailure) {
            try {
                NativeDubboBridge.closeClient(nativeClientId);
            } catch (RuntimeException | LinkageError closeFailure) {
                constructionFailure.addSuppressed(closeFailure);
            }
            throw constructionFailure;
        }
    }

    public void start() {
        watcher.start();
    }

    public T proxy() {
        T current = proxy;
        if (current == null) {
            synchronized (this) {
                current = proxy;
                if (current == null) {
                    current = createProxy();
                    proxy = current;
                }
            }
        }
        return current;
    }

    public <R> NativeDubboMethodInvoker<R> methodInvoker(String methodName, Class<R> returnType, Class<?>... parameterTypes) {
        Class<?>[] types = parameterTypes == null ? new Class<?>[0] : parameterTypes;
        try {
            return new NativeDubboMethodInvoker<>(
                    nativeClientId,
                    config,
                    spec,
                    serviceInterface.getMethod(methodName, types),
                    returnType,
                    legacyDecodeExecutor);
        } catch (NoSuchMethodException e) {
            throw new DubboConsumerException("No native Dubbo method "
                    + serviceInterface.getName() + "." + methodName, e);
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            watcher.close();
        } finally {
            NativeDubboBridge.closeClient(nativeClientId);
        }
    }

    private T createProxy() {
        Object created = Proxy.newProxyInstance(
                serviceInterface.getClassLoader(),
                new Class<?>[] {serviceInterface},
                new NativeInvocationHandler());
        return serviceInterface.cast(created);
    }

    private final class NativeInvocationHandler implements InvocationHandler {
        private final ConcurrentHashMap<Method, NativeDubboMethodInvoker<?>> methodCache = new ConcurrentHashMap<>();

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> "NativeDubboProxy(" + serviceInterface.getName() + ")";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> method.invoke(this, args);
                };
            }
            NativeDubboMethodInvoker<?> invoker = methodCache.computeIfAbsent(method,
                    ignored -> new NativeDubboMethodInvoker<>(
                            nativeClientId,
                            config,
                            spec,
                            method,
                            method.getReturnType(),
                            legacyDecodeExecutor));
            Object[] actualArgs = args == null || args.length == 0 ? EMPTY_ARGS : args;
            return invoker.invoke(actualArgs);
        }
    }

    private static int valueOrDefault(Integer value, int defaultValue) {
        return value == null ? defaultValue : value;
    }

    private static int createNativeClient(DubboConsumerConfig config, DubboReferenceSpec<?> spec) {
        return NativeDubboBridge.createClient(
                valueOrDefault(spec.timeoutMs(), config.timeoutMs()),
                config.maxInflight(),
                config.maxResponseBytes(),
                config.nativeConnectionsPerEndpoint(),
                config.nativeMaxIdleConnectionsPerEndpoint());
    }
}
