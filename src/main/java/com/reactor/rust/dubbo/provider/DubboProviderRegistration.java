package com.reactor.rust.dubbo.provider;

import org.apache.dubbo.common.URL;

public interface DubboProviderRegistration extends AutoCloseable {

    void register(Class<?> serviceType, URL providerUrl) throws Exception;

    @Override
    void close() throws Exception;
}
