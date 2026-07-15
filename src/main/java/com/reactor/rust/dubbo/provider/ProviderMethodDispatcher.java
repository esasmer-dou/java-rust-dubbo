package com.reactor.rust.dubbo.provider;

/**
 * Explicit provider dispatch seam. Generated dispatchers may implement this interface to avoid
 * reflection and method-signature lookup entirely.
 */
@FunctionalInterface
public interface ProviderMethodDispatcher<T> {

    Object invoke(
            T service,
            String methodName,
            Class<?>[] parameterTypes,
            Object[] arguments) throws Throwable;
}
