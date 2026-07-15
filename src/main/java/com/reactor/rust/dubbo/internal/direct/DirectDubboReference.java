package com.reactor.rust.dubbo.internal.direct;

import com.reactor.rust.dubbo.DubboConsumerConfig;
import com.reactor.rust.dubbo.DubboConsumerException;
import com.reactor.rust.dubbo.DubboMethodInvoker;
import com.reactor.rust.dubbo.DubboReferenceSpec;
import com.reactor.rust.dubbo.internal.registry.ProviderWatcher;
import com.reactor.rust.dubbo.internal.registry.ZookeeperRegistryClient;

import java.lang.reflect.Proxy;
import java.util.concurrent.Executor;

public final class DirectDubboReference<T> implements AutoCloseable {

    private final Class<T> serviceInterface;
    private final MinimalDubboInvoker<T> invoker;
    private final ProviderWatcher watcher;
    private volatile T proxy;
    private boolean closed;

    public DirectDubboReference(
            DubboConsumerConfig config,
            DubboReferenceSpec<T> spec,
            ZookeeperRegistryClient zookeeper,
            Executor refreshExecutor) {
        this.serviceInterface = spec.serviceInterface();
        this.invoker = new MinimalDubboInvoker<>(config, spec);
        this.watcher = new ZookeeperProviderWatcher<>(
                config,
                spec,
                zookeeper,
                refreshExecutor,
                invoker);
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
                    current = createProxy(serviceInterface, invoker);
                    proxy = current;
                }
            }
        }
        return current;
    }

    public <R> DubboMethodInvoker<R> methodInvoker(String methodName, Class<R> returnType, Class<?>... parameterTypes) {
        Class<?>[] types = parameterTypes == null ? new Class<?>[0] : parameterTypes;
        try {
            return new DubboMethodInvoker<>(
                    serviceInterface,
                    invoker,
                    serviceInterface.getMethod(methodName, types),
                    returnType);
        } catch (NoSuchMethodException e) {
            throw new DubboConsumerException("No Dubbo method " + serviceInterface.getName() + "." + methodName, e);
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
            invoker.destroy();
        }
    }

    private static <T> T createProxy(Class<T> serviceInterface, MinimalDubboInvoker<T> invoker) {
        Object proxy = Proxy.newProxyInstance(
                serviceInterface.getClassLoader(),
                new Class<?>[] {serviceInterface},
                new DirectDubboInvocationHandler<>(serviceInterface, invoker));
        return serviceInterface.cast(proxy);
    }
}
