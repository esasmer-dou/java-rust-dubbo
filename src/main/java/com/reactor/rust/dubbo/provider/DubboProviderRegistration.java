package com.reactor.rust.dubbo.provider;

import org.apache.dubbo.common.URL;

public interface DubboProviderRegistration extends AutoCloseable {

    DubboProviderRegistration DISABLED = new DubboProviderRegistration() {
        @Override
        public void register(Class<?> serviceType, URL providerUrl) {
        }

        @Override
        public void close() {
        }
    };

    static DubboProviderRegistration disabled() {
        return DISABLED;
    }

    void register(Class<?> serviceType, URL providerUrl) throws Exception;

    @Override
    void close() throws Exception;
}
