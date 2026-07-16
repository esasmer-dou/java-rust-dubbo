package com.reactor.rust.dubbo.provider;

import com.reactor.rust.dubbo.config.DubboApplicationProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DubboProviderRuntimeTuningTest {

    private static final String ALLOWED_CLASS_LIST = "dubbo.security.serialize.allowedClassList";

    @AfterEach
    void clearSystemProperty() {
        System.clearProperty(ALLOWED_CLASS_LIST);
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
