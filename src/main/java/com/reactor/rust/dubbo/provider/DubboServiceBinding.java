package com.reactor.rust.dubbo.provider;

import java.util.Objects;

/**
 * Explicit service-to-implementation binding used by declarative provider modules.
 */
public final class DubboServiceBinding<T> {

    private final Class<T> serviceType;
    private final T implementation;

    private DubboServiceBinding(Class<T> serviceType, T implementation) {
        this.serviceType = Objects.requireNonNull(serviceType, "serviceType");
        this.implementation = Objects.requireNonNull(implementation, "implementation");
        if (!serviceType.isInstance(implementation)) {
            throw new IllegalArgumentException(
                    implementation.getClass().getName() + " does not implement " + serviceType.getName());
        }
    }

    public static <T> DubboServiceBinding<T> service(Class<T> serviceType, T implementation) {
        return new DubboServiceBinding<>(serviceType, implementation);
    }

    public Class<T> serviceType() {
        return serviceType;
    }

    public T implementation() {
        return implementation;
    }
}
