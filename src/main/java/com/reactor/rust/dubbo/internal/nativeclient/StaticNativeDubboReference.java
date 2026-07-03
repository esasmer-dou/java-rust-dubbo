package com.reactor.rust.dubbo.internal.nativeclient;

import com.reactor.rust.dubbo.DubboConsumerConfig;
import com.reactor.rust.dubbo.DubboConsumerException;
import com.reactor.rust.dubbo.DubboReferenceSpec;
import com.reactor.rust.dubbo.NativeDubboBridge;
import com.reactor.rust.dubbo.NativeDubboMethodInvoker;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class StaticNativeDubboReference<T> implements NativeDubboReferenceHandle<T> {

    private static final Object[] EMPTY_ARGS = new Object[0];

    private final DubboConsumerConfig config;
    private final DubboReferenceSpec<T> spec;
    private final Class<T> serviceInterface;
    private final int nativeClientId;
    private final String staticProviders;
    private volatile T proxy;

    public StaticNativeDubboReference(DubboConsumerConfig config, DubboReferenceSpec<T> spec) {
        this.config = Objects.requireNonNull(config, "config");
        this.spec = Objects.requireNonNull(spec, "spec");
        this.serviceInterface = spec.serviceInterface();
        this.nativeClientId = createNativeClient(config, spec);
        this.staticProviders = config.providers();
    }

    @Override
    public void start() {
        NativeDubboBridge.updateProviders(nativeClientId, staticProviders);
    }

    @Override
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

    @Override
    public <R> NativeDubboMethodInvoker<R> methodInvoker(String methodName, Class<R> returnType, Class<?>... parameterTypes) {
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
        NativeDubboBridge.updateProviders(nativeClientId, "");
        NativeDubboBridge.closeClient(nativeClientId);
    }

    private T createProxy() {
        Object created = Proxy.newProxyInstance(
                serviceInterface.getClassLoader(),
                new Class<?>[] {serviceInterface},
                new StaticNativeInvocationHandler());
        return serviceInterface.cast(created);
    }

    private final class StaticNativeInvocationHandler implements InvocationHandler {
        private final ConcurrentHashMap<Method, NativeDubboMethodInvoker<?>> methodCache = new ConcurrentHashMap<>();

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> "StaticNativeDubboProxy(" + serviceInterface.getName() + ")";
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
