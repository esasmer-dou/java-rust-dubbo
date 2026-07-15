package com.reactor.rust.dubbo.provider;

import com.reactor.rust.dubbo.config.DubboApplicationProperties;

import java.util.Objects;

/**
 * Applies provider runtime defaults before Netty or Dubbo initialize their static state.
 */
public final class DubboProviderRuntimeTuning {

    private DubboProviderRuntimeTuning() {}

    public static void applyLowRssDefaults(DubboApplicationProperties properties) {
        Objects.requireNonNull(properties, "properties");
        setIfAbsent(properties, "io.netty.allocator.numDirectArenas", "1");
        setIfAbsent(properties, "io.netty.allocator.numHeapArenas", "1");
        setIfAbsent(properties, "io.netty.recycler.maxCapacityPerThread", "0");
        setIfAbsent(properties, "io.netty.noPreferDirect", "true");
        setIfAbsent(properties, "dubbo.application.logger", "slf4j");
        setIfAbsent(properties, "dubbo.application.qos.enable", "false");
        setIfAbsent(properties, "dubbo.metrics.enable", "false");
        setIfAbsent(properties, "dubbo.tracing.enabled", "false");
    }

    private static void setIfAbsent(
            DubboApplicationProperties properties,
            String key,
            String defaultValue) {
        if (System.getProperty(key) == null) {
            System.setProperty(key, properties.get(key, defaultValue));
        }
    }
}
