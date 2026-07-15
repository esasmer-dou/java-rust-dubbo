package com.reactor.rust.dubbo.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DubboApplicationPropertiesTest {

    @AfterEach
    void clearOverrides() {
        System.clearProperty("dubbo.sample.limit");
    }

    @Test
    void runtimeValueOverridesFileValue() {
        Properties values = new Properties();
        values.setProperty("dubbo.sample.limit", "2");
        System.setProperty("dubbo.sample.limit", "4");

        assertEquals(4, DubboApplicationProperties.from(values).getInt("dubbo.sample.limit"));
    }

    @Test
    void rejectsInvalidBoolean() {
        Properties values = new Properties();
        values.setProperty("dubbo.sample.enabled", "perhaps");

        assertThrows(
                IllegalArgumentException.class,
                () -> DubboApplicationProperties.from(values).getBoolean("dubbo.sample.enabled"));
    }

    @Test
    void booleanDefaultIsUsedOnlyWhenAValueIsMissing() {
        assertFalse(DubboApplicationProperties.from(new Properties())
                .getBoolean("dubbo.sample.enabled", false));
    }
}
