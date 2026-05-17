package com.reactor.rust.dubbo.internal.registry;

public interface ProviderWatcher extends AutoCloseable {

    void start();

    @Override
    void close();
}
