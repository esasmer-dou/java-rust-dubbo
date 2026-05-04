package com.reactor.rust.dubbo;

import java.lang.reflect.Proxy;
import java.util.concurrent.Executor;

final class DirectDubboReference<T> implements AutoCloseable {

    private final Class<T> serviceInterface;
    private final MinimalDubboInvoker<T> invoker;
    private final ProviderWatcher watcher;
    private volatile T proxy;

    DirectDubboReference(
            DubboConsumerConfig config,
            DubboReferenceSpec<T> spec,
            Object zookeeper,
            Executor refreshExecutor) {
        this.serviceInterface = spec.serviceInterface();
        this.invoker = new MinimalDubboInvoker<>(config, spec);
        this.watcher = new ZookeeperProviderWatcher<>(
                config,
                spec,
                (ZookeeperRegistryClient) zookeeper,
                refreshExecutor,
                invoker);
    }

    void start() {
        watcher.start();
    }

    T proxy() {
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

    <R> DubboMethodInvoker<R> methodInvoker(String methodName, Class<R> returnType, Class<?>... parameterTypes) {
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
    public void close() {
        watcher.close();
        invoker.destroy();
    }

    private static <T> T createProxy(Class<T> serviceInterface, MinimalDubboInvoker<T> invoker) {
        Object proxy = Proxy.newProxyInstance(
                serviceInterface.getClassLoader(),
                new Class<?>[] {serviceInterface},
                new DirectDubboInvocationHandler<>(serviceInterface, invoker));
        return serviceInterface.cast(proxy);
    }
}
