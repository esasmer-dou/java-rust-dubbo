package com.reactor.rust.dubbo;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DubboConsumerConfigTest {

    @Test
    void normalizesRegistryHostPortToZookeeperUrl() {
        DubboConsumerConfig config = DubboConsumerConfig.builder()
                .registryAddress("127.0.0.1:2181")
                .build();

        assertEquals("zookeeper://127.0.0.1:2181", config.registryAddress());
    }

    @Test
    void readsPropertiesWithFrameworkPrefix() {
        Properties properties = new Properties();
        properties.setProperty("reactor.dubbo.application-name", "orders-api");
        properties.setProperty("reactor.dubbo.registry-address", "zk.internal:2181");
        properties.setProperty("reactor.dubbo.registry-root", "/prod-dubbo/");
        properties.setProperty("reactor.dubbo.timeout-ms", "750");
        properties.setProperty("reactor.dubbo.retries", "0");
        properties.setProperty("reactor.dubbo.refer-thread-num", "2");
        properties.setProperty("reactor.dubbo.max-inflight", "128");
        properties.setProperty("reactor.dubbo.max-response-bytes", "4096");
        properties.setProperty("reactor.dubbo.native-connections-per-endpoint", "24");
        properties.setProperty("reactor.dubbo.native-async-workers", "6");
        properties.setProperty("reactor.dubbo.native-async-queue-capacity", "256");
        properties.setProperty("reactor.dubbo.providers", "10.0.0.1:20880, 10.0.0.2:20880");
        properties.setProperty("reactor.dubbo.runtime-profile", "throughput");
        properties.setProperty("reactor.dubbo.transport", "official");

        DubboConsumerConfig config = DubboConsumerConfig.fromProperties(properties);

        assertEquals("orders-api", config.applicationName());
        assertEquals("zookeeper://zk.internal:2181", config.registryAddress());
        assertEquals("prod-dubbo", config.registryRoot());
        assertEquals(750, config.timeoutMs());
        assertEquals(0, config.retries());
        assertEquals(2, config.referThreadNum());
        assertEquals(128, config.maxInflight());
        assertEquals(4096, config.maxResponseBytes());
        assertEquals(24, config.nativeConnectionsPerEndpoint());
        assertEquals(6, config.nativeAsyncWorkers());
        assertEquals(256, config.nativeAsyncQueueCapacity());
        assertEquals("10.0.0.1:20880,10.0.0.2:20880", config.providers());
        assertEquals("throughput", config.runtimeProfile());
        assertEquals("official", config.transport());
    }

    @Test
    void rejectsUnsupportedClusterAndLoadBalanceStrategies() {
        assertThrows(IllegalArgumentException.class, () -> DubboConsumerConfig.builder()
                .cluster("zone-aware")
                .build());
        assertThrows(IllegalArgumentException.class, () -> DubboConsumerConfig.builder()
                .loadbalance("leastactive")
                .build());
        assertThrows(IllegalArgumentException.class, () -> DubboConsumerConfig.builder()
                .runtimeProfile("full-dubbo")
                .build());
        assertThrows(IllegalArgumentException.class, () -> DubboConsumerConfig.builder()
                .transport("grpc")
                .build());
    }

    @Test
    void acceptsBalancedDubboRuntimeProfile() {
        DubboConsumerConfig config = DubboConsumerConfig.builder()
                .runtimeProfile("balanced-dubbo")
                .build();

        assertEquals("balanced-dubbo", config.runtimeProfile());
    }

}
