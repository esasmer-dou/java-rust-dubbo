package com.reactor.rust.dubbo.provider;

import org.junit.jupiter.api.Test;

import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

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
}
