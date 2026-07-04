package com.reactor.rust.dubbo.support;

import com.reactor.rust.dubbo.DubboConsumerConfig;
import com.reactor.rust.dubbo.DubboReferenceSpec;

import java.util.Locale;
import java.util.Objects;
import java.util.Properties;

public final class DubboConsumerSupport {

    public static final String DEFAULT_DISCOVERY_PROPERTY = "reactor.dubbo.discovery";

    private final Properties properties;
    private final String discoveryProperty;

    private DubboConsumerSupport(Properties properties, String discoveryProperty) {
        this.properties = copy(properties);
        this.discoveryProperty = requireText(discoveryProperty, "discoveryProperty");
    }

    public static DubboConsumerSupport fromProperties() {
        return new DubboConsumerSupport(new Properties(), DEFAULT_DISCOVERY_PROPERTY);
    }

    public static DubboConsumerSupport fromProperties(Properties properties) {
        return new DubboConsumerSupport(properties, DEFAULT_DISCOVERY_PROPERTY);
    }

    public DubboConsumerSupport discoveryProperty(String discoveryProperty) {
        return new DubboConsumerSupport(properties, discoveryProperty);
    }

    public DubboConsumerConfig config() {
        DubboConsumerConfig config = DubboConsumerConfig.fromProperties(properties);
        return zookeeperDiscovery() ? withoutStaticProviders(config) : config;
    }

    public DubboConsumerConfig staticConfig() {
        return DubboConsumerConfig.fromProperties(properties);
    }

    public <T> DubboReferenceSpec<T> reference(Class<T> serviceType) {
        return referenceBuilder(serviceType).build();
    }

    public <T> DubboReferenceSpec.Builder<T> referenceBuilder(Class<T> serviceType) {
        DubboConsumerConfig config = DubboConsumerConfig.fromProperties(properties);
        return DubboReferenceSpec.builder(serviceType)
                .timeoutMs(config.timeoutMs())
                .retries(config.retries())
                .check(config.check())
                .lazy(config.lazy());
    }

    public boolean zookeeperDiscovery() {
        String discovery = readString(discoveryProperty, "static")
                .trim()
                .toLowerCase(Locale.ROOT);
        return "zookeeper".equals(discovery) || "zk".equals(discovery);
    }

    public boolean booleanProperty(String key, boolean defaultValue) {
        String value = readString(key, Boolean.toString(defaultValue));
        if ("true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value) || "0".equals(value) || "no".equalsIgnoreCase(value)) {
            return false;
        }
        throw new IllegalArgumentException("Property must be a boolean: " + key + "=" + value);
    }

    private String readString(String key, String defaultValue) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            value = System.getenv(toEnvKey(key));
        }
        if (value == null || value.isBlank()) {
            value = properties.getProperty(key);
        }
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static DubboConsumerConfig withoutStaticProviders(DubboConsumerConfig config) {
        return DubboConsumerConfig.builder()
                .applicationName(config.applicationName())
                .registryAddress(config.registryAddress())
                .registryRoot(config.registryRoot())
                .providers("")
                .registryTimeoutMs(config.registryTimeoutMs())
                .registrySessionTimeoutMs(config.registrySessionTimeoutMs())
                .registryCheck(config.registryCheck())
                .protocol(config.protocol())
                .serialization(config.serialization())
                .timeoutMs(config.timeoutMs())
                .retries(config.retries())
                .check(config.check())
                .lazy(config.lazy())
                .connections(config.connections())
                .shareConnections(config.shareConnections())
                .referThreadNum(config.referThreadNum())
                .maxInflight(config.maxInflight())
                .maxResponseBytes(config.maxResponseBytes())
                .nativeConnectionsPerEndpoint(config.nativeConnectionsPerEndpoint())
                .nativeMaxIdleConnectionsPerEndpoint(config.nativeMaxIdleConnectionsPerEndpoint())
                .nativeAsyncWorkers(config.nativeAsyncWorkers())
                .nativeAsyncQueueCapacity(config.nativeAsyncQueueCapacity())
                .nativeAsyncTransport(config.nativeAsyncTransport())
                .runtimeProfile(config.runtimeProfile())
                .transport(config.transport())
                .cluster(config.cluster())
                .loadbalance(config.loadbalance())
                .build();
    }

    private static Properties copy(Properties properties) {
        Objects.requireNonNull(properties, "properties");
        Properties copy = new Properties();
        copy.putAll(properties);
        return copy;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private static String toEnvKey(String key) {
        return key.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
    }
}
