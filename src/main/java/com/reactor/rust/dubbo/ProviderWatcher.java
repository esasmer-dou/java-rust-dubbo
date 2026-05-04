package com.reactor.rust.dubbo;

interface ProviderWatcher extends AutoCloseable {

    void start();

    @Override
    void close();
}
