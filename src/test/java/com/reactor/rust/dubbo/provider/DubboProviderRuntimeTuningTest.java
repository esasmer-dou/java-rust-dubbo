package com.reactor.rust.dubbo.provider;

import com.reactor.rust.dubbo.config.DubboApplicationProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DubboProviderRuntimeTuningTest {

    private static final String ALLOWED_CLASS_LIST = "dubbo.security.serialize.allowedClassList";
    private static final String[] KEYS = {ALLOWED_CLASS_LIST, "io.netty.allocator.numDirectArenas",
            "io.netty.allocator.numHeapArenas", "io.netty.allocator.maxOrder", "io.netty.allocator.smallCacheSize",
            "io.netty.allocator.normalCacheSize", "io.netty.allocator.useCacheForAllThreads",
            "io.netty.recycler.maxCapacityPerThread", "io.netty.noPreferDirect", "dubbo.application.logger",
            "dubbo.application.qos.enable", "dubbo.metrics.enable", "dubbo.tracing.enabled"};
    private final Properties original = new Properties();

    @BeforeEach
    void isolateProperties() {
        for (String key : KEYS) {
            String value = System.clearProperty(key);
            if (value != null) original.setProperty(key, value);
        }
    }

    @AfterEach
    void clearSystemProperty() {
        for (String key : KEYS) {
            System.clearProperty(key);
            if (original.containsKey(key)) System.setProperty(key, original.getProperty(key));
        }
    }

    @Test
    void boundsArenaChunksAndDisablesPerThreadBufferCaches() {
        DubboProviderRuntimeTuning.applyLowRssDefaults(DubboApplicationProperties.from(new Properties()));
        assertEquals("3", System.getProperty("io.netty.allocator.maxOrder"));
        assertEquals("0", System.getProperty("io.netty.allocator.smallCacheSize"));
        assertEquals("0", System.getProperty("io.netty.allocator.normalCacheSize"));
        assertEquals("false", System.getProperty("io.netty.allocator.useCacheForAllThreads"));
    }

    @Test
    void preservesApplicationAndJvmAllocatorOverrides() {
        Properties values = new Properties();
        values.setProperty("io.netty.allocator.maxOrder", "5");
        values.setProperty("io.netty.allocator.smallCacheSize", "16");
        System.setProperty("io.netty.allocator.smallCacheSize", "32");
        DubboProviderRuntimeTuning.applyLowRssDefaults(DubboApplicationProperties.from(values));
        assertEquals("5", System.getProperty("io.netty.allocator.maxOrder"));
        assertEquals("32", System.getProperty("io.netty.allocator.smallCacheSize"));
    }

    @Test
    void promotesConfiguredSerializationAllowlistBeforeDubboStarts() {
        Properties values = new Properties();
        values.setProperty(ALLOWED_CLASS_LIST, "com.example.dto.");

        DubboProviderRuntimeTuning.applyLowRssDefaults(DubboApplicationProperties.from(values));

        assertEquals("com.example.dto.", System.getProperty(ALLOWED_CLASS_LIST));
    }

    @Test
    void preservesExplicitJvmSerializationAllowlist() {
        System.setProperty(ALLOWED_CLASS_LIST, "com.runtime.dto.");
        Properties values = new Properties();
        values.setProperty(ALLOWED_CLASS_LIST, "com.config.dto.");

        DubboProviderRuntimeTuning.applyLowRssDefaults(DubboApplicationProperties.from(values));

        assertEquals("com.runtime.dto.", System.getProperty(ALLOWED_CLASS_LIST));
    }
}
