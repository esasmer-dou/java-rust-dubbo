package com.reactor.rust.dubbo.internal.nativeclient;

import com.reactor.rust.dubbo.DubboConsumerConfig;
import com.reactor.rust.dubbo.DubboReferenceSpec;
import com.reactor.rust.dubbo.internal.registry.ZookeeperRegistryClient;

import java.util.concurrent.Executor;

public final class ZookeeperNativeDubboReferenceFactory {

    private ZookeeperNativeDubboReferenceFactory() {
    }

    public static AutoCloseable openRegistry(DubboConsumerConfig config, Executor refreshExecutor) {
        ZookeeperRegistryClient zookeeper = new ZookeeperRegistryClient(config, refreshExecutor);
        zookeeper.start();
        return zookeeper;
    }

    public static <T> NativeDubboReferenceHandle<T> createReference(
            DubboConsumerConfig config,
            DubboReferenceSpec<T> spec,
            AutoCloseable zookeeper,
            Executor refreshExecutor
    ) {
        return new NativeDubboReference<>(
                config,
                spec,
                (ZookeeperRegistryClient) zookeeper,
                refreshExecutor);
    }
}
