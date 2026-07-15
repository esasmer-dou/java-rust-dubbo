package com.reactor.rust.dubbo.provider;

import com.reactor.rust.dubbo.config.DubboApplicationProperties;
import org.junit.jupiter.api.Test;

import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DubboProviderApplicationTest {

    @Test
    void closesModuleResourcesWhenConfigurationFails() {
        AtomicBoolean closed = new AtomicBoolean();
        DubboProviderApplication.Builder builder = DubboProviderApplication.builder(new Properties())
                .module(context -> {
                    context.manage(() -> closed.set(true));
                    throw new IllegalStateException("configuration failed");
                });

        assertThrows(IllegalStateException.class, builder::start);
        assertTrue(closed.get());
    }

    @Test
    void simpleLauncherExposesPropertiesAndOwnsResources() {
        Properties values = new Properties();
        values.setProperty("reactor.dubbo.registry-enabled", "false");
        AtomicBoolean closed = new AtomicBoolean();

        assertThrows(IllegalStateException.class, () -> DubboProviderApplication.start(
                DubboApplicationProperties.from(values),
                "test",
                context -> {
                    assertFalse(context.properties().getBoolean("reactor.dubbo.registry-enabled"));
                    context.manage(() -> closed.set(true));
                    throw new IllegalStateException("configuration failed");
                }));

        assertTrue(closed.get());
    }
}
