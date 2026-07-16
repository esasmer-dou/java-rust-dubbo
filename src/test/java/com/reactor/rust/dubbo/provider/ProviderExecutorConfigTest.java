package com.reactor.rust.dubbo.provider;

import org.apache.dubbo.common.URL;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProviderExecutorConfigTest {

    @Test
    void supportUsesBoundedLowRssExecutorDefaults() {
        Properties values = requiredProviderProperties();

        PlainDubboProvider.ProviderConfig config = DubboProviderSupport.fromProperties(values)
                .providerConfig(false);
        URL url = PlainDubboProvider.providerUrl(
                TestService.class,
                config,
                PlainDubboProvider.ServiceExecutionConfig.bounded(2));

        assertEquals("eager", url.getParameter("threadpool"));
        assertEquals(1, url.getParameter("corethreads", 0));
        assertEquals(8, url.getParameter("threads", 0));
        assertEquals(16, url.getParameter("queues", 0));
        assertEquals(30_000, url.getParameter("alive", 0));
        assertEquals(1, url.getParameter("iothreads", 0));
    }

    @Test
    void explicitExecutorPropertiesOverrideDefaults() {
        Properties values = requiredProviderProperties();
        values.setProperty("dubbo.provider.executor.thread-pool", "fixed");
        values.setProperty("dubbo.provider.executor.core-threads", "2");
        values.setProperty("dubbo.provider.executor.max-threads", "4");
        values.setProperty("dubbo.provider.executor.queue-capacity", "8");
        values.setProperty("dubbo.provider.executor.idle-timeout-ms", "60000");
        values.setProperty("dubbo.provider.executor.io-threads", "2");

        ProviderExecutorConfig config = DubboProviderSupport.fromProperties(values)
                .providerConfig(false)
                .executor();

        assertEquals(new ProviderExecutorConfig("fixed", 2, 4, 8, 60_000, 2), config);
    }

    @Test
    void rejectsUnboundedOrInconsistentExecutorSettings() {
        assertThrows(IllegalArgumentException.class,
                () -> new ProviderExecutorConfig("cached", 1, 8, 16, 30_000, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new ProviderExecutorConfig("eager", 4, 2, 16, 30_000, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new ProviderExecutorConfig("eager", 1, 8, -1, 30_000, 1));
    }

    private static Properties requiredProviderProperties() {
        Properties values = new Properties();
        values.setProperty("dubbo.provider.application-name", "test-provider");
        values.setProperty("dubbo.provider.host", "127.0.0.1");
        values.setProperty("dubbo.provider.bind-host", "0.0.0.0");
        values.setProperty("dubbo.provider.port", "20880");
        return values;
    }

    private interface TestService {
        String get();
    }
}
