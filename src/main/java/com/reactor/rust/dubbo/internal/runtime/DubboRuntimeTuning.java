package com.reactor.rust.dubbo.internal.runtime;

import com.reactor.rust.dubbo.DubboConsumerConfig;
import com.reactor.rust.dubbo.DubboConsumerException;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class DubboRuntimeTuning {

    private static final AtomicReference<String> APPLIED_PROFILE = new AtomicReference<>();

    private DubboRuntimeTuning() {}

    public static void apply(DubboConsumerConfig config) {
        Objects.requireNonNull(config, "config");
        String profile = config.runtimeProfile();
        String previous = APPLIED_PROFILE.get();
        if (previous != null) {
            if (!previous.equals(profile)) {
                throw new DubboConsumerException("Dubbo runtime profile already applied as " + previous
                        + "; cannot switch to " + profile + " after runtime initialization");
            }
            return;
        }
        if (!APPLIED_PROFILE.compareAndSet(null, profile)) {
            apply(config);
            return;
        }
        if ("default".equals(profile)) {
            return;
        }
        forceNoEpoll();
        if ("low-rss".equals(profile)) {
            applyLowRssNettyAllocator();
        }
    }

    private static void forceNoEpoll() {
        setIfAbsent("netty.epoll.enable", "false");
    }

    private static void applyLowRssNettyAllocator() {
        setIfAbsent("io.netty.noPreferDirect", "true");
        setIfAbsent("io.netty.allocator.numDirectArenas", "1");
        setIfAbsent("io.netty.allocator.numHeapArenas", "1");
        setIfAbsent("io.netty.allocator.maxOrder", "3");
        setIfAbsent("io.netty.allocator.smallCacheSize", "0");
        setIfAbsent("io.netty.allocator.normalCacheSize", "0");
        setIfAbsent("io.netty.allocator.useCacheForAllThreads", "false");
        setIfAbsent("io.netty.recycler.maxCapacityPerThread", "0");
    }

    private static void setIfAbsent(String key, String value) {
        if (System.getProperty(key) == null) {
            System.setProperty(key, value);
        }
    }
}
