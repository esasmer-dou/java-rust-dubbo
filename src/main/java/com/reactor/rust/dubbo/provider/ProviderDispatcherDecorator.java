package com.reactor.rust.dubbo.provider;

/** Explicit startup-only dispatcher decoration; the default provider creates no wrapper. */
public interface ProviderDispatcherDecorator {
    <T> ProviderMethodDispatcher<T> decorate(Class<T> serviceType, ProviderMethodDispatcher<T> dispatcher);
}
