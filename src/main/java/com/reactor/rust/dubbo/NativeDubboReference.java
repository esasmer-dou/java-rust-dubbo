package com.reactor.rust.dubbo;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

final class NativeDubboReference<T> implements AutoCloseable {

    private final DubboConsumerConfig config;
    private final DubboReferenceSpec<T> spec;
    private final Class<T> serviceInterface;
    private final int nativeClientId;
    private final ProviderWatcher watcher;
    private final String staticProviders;
    private volatile T proxy;

    NativeDubboReference(DubboConsumerConfig config, DubboReferenceSpec<T> spec) {
        this.config = Objects.requireNonNull(config, "config");
        this.spec = Objects.requireNonNull(spec, "spec");
        this.serviceInterface = spec.serviceInterface();
        this.nativeClientId = createNativeClient(config, spec);
        this.watcher = null;
        this.staticProviders = config.providers();
    }

    NativeDubboReference(
            DubboConsumerConfig config,
            DubboReferenceSpec<T> spec,
            Object zookeeper,
            Executor refreshExecutor) {
        this.config = Objects.requireNonNull(config, "config");
        this.spec = Objects.requireNonNull(spec, "spec");
        this.serviceInterface = spec.serviceInterface();
        this.nativeClientId = createNativeClient(config, spec);
        this.watcher = new NativeDubboProviderWatcher<>(
                config,
                spec,
                (ZookeeperRegistryClient) zookeeper,
                refreshExecutor,
                nativeClientId);
        this.staticProviders = "";
    }

    void start() {
        if (watcher == null) {
            NativeDubboBridge.updateProviders(nativeClientId, staticProviders);
        } else {
            watcher.start();
        }
    }

    T proxy() {
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

    <R> NativeDubboMethodInvoker<R> methodInvoker(String methodName, Class<R> returnType, Class<?>... parameterTypes) {
        Class<?>[] types = parameterTypes == null ? new Class<?>[0] : parameterTypes;
        try {
            return new NativeDubboMethodInvoker<>(
                    nativeClientId,
                    config,
                    spec,
                    serviceInterface.getMethod(methodName, types),
                    returnType);
        } catch (NoSuchMethodException e) {
            throw new DubboConsumerException("No native Dubbo method "
                    + serviceInterface.getName() + "." + methodName, e);
        }
    }

    @Override
    public void close() {
        if (watcher != null) {
            watcher.close();
        } else {
            NativeDubboBridge.updateProviders(nativeClientId, "");
        }
        NativeDubboBridge.closeClient(nativeClientId);
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
                            method.getReturnType()));
            Object[] actualArgs = args == null ? new Object[0] : Arrays.copyOf(args, args.length);
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
                config.nativeConnectionsPerEndpoint());
    }
}
