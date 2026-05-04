package com.reactor.rust.dubbo;

import java.util.Locale;
import java.util.Objects;
import java.util.Properties;

public final class DubboConsumerConfig {

    public static final String PROPERTY_PREFIX = "reactor.dubbo.";
    public static final String DEFAULT_APPLICATION_NAME = "rust-java-rest-dubbo-consumer";
    public static final String DEFAULT_REGISTRY_ADDRESS = "zookeeper://127.0.0.1:2181";
    public static final String DEFAULT_PROTOCOL = "dubbo";
    public static final String DEFAULT_SERIALIZATION = "hessian2";
    public static final String DEFAULT_CLUSTER = "failfast";
    public static final String DEFAULT_LOADBALANCE = "random";
    public static final String DEFAULT_REGISTRY_ROOT = "dubbo";
    public static final String DEFAULT_RUNTIME_PROFILE = "low-rss";
    public static final String RUNTIME_PROFILE_BALANCED_DUBBO = "balanced-dubbo";
    public static final String DEFAULT_TRANSPORT = "native";

    private final String applicationName;
    private final String registryAddress;
    private final String registryRoot;
    private final String providers;
    private final int registryTimeoutMs;
    private final int registrySessionTimeoutMs;
    private final boolean registryCheck;
    private final String protocol;
    private final String serialization;
    private final int timeoutMs;
    private final int retries;
    private final boolean check;
    private final boolean lazy;
    private final int connections;
    private final int shareConnections;
    private final int referThreadNum;
    private final int maxInflight;
    private final int maxResponseBytes;
    private final int nativeConnectionsPerEndpoint;
    private final int nativeAsyncWorkers;
    private final int nativeAsyncQueueCapacity;
    private final String runtimeProfile;
    private final String transport;
    private final String cluster;
    private final String loadbalance;

    private DubboConsumerConfig(Builder builder) {
        this.applicationName = requireText(builder.applicationName, "applicationName");
        this.registryAddress = normalizeRegistryAddress(requireText(builder.registryAddress, "registryAddress"));
        this.registryRoot = normalizeRegistryRoot(builder.registryRoot);
        this.providers = normalizeProviders(builder.providers);
        this.registryTimeoutMs = requirePositive(builder.registryTimeoutMs, "registryTimeoutMs");
        this.registrySessionTimeoutMs = requirePositive(builder.registrySessionTimeoutMs, "registrySessionTimeoutMs");
        this.registryCheck = builder.registryCheck;
        this.protocol = requireText(builder.protocol, "protocol");
        this.serialization = requireText(builder.serialization, "serialization");
        this.timeoutMs = requirePositive(builder.timeoutMs, "timeoutMs");
        this.retries = requireNonNegative(builder.retries, "retries");
        this.check = builder.check;
        this.lazy = builder.lazy;
        this.connections = requireNonNegative(builder.connections, "connections");
        this.shareConnections = requireNonNegative(builder.shareConnections, "shareConnections");
        this.referThreadNum = requirePositive(builder.referThreadNum, "referThreadNum");
        this.maxInflight = requireNonNegative(builder.maxInflight, "maxInflight");
        this.maxResponseBytes = requirePositive(builder.maxResponseBytes, "maxResponseBytes");
        this.nativeConnectionsPerEndpoint = requirePositive(builder.nativeConnectionsPerEndpoint, "nativeConnectionsPerEndpoint");
        this.nativeAsyncWorkers = requirePositive(builder.nativeAsyncWorkers, "nativeAsyncWorkers");
        this.nativeAsyncQueueCapacity = requirePositive(builder.nativeAsyncQueueCapacity, "nativeAsyncQueueCapacity");
        this.runtimeProfile = normalizeRuntimeProfile(builder.runtimeProfile);
        this.transport = normalizeTransport(builder.transport);
        this.cluster = requireText(builder.cluster, "cluster");
        if (!"failfast".equals(this.cluster) && !"failover".equals(this.cluster)) {
            throw new IllegalArgumentException("cluster must be failfast or failover for the lite adapter");
        }
        this.loadbalance = requireText(builder.loadbalance, "loadbalance");
        if (!"random".equals(this.loadbalance) && !"roundrobin".equals(this.loadbalance)) {
            throw new IllegalArgumentException("loadbalance must be random or roundrobin for the lite adapter");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static DubboConsumerConfig fromProperties() {
        return fromProperties(new Properties());
    }

    public static DubboConsumerConfig fromProperties(Properties properties) {
        Objects.requireNonNull(properties, "properties");
        Builder builder = builder();
        builder.applicationName(readString(properties, "application-name", builder.applicationName));
        builder.registryAddress(readString(properties, "registry-address", builder.registryAddress));
        builder.registryRoot(readString(properties, "registry-root", builder.registryRoot));
        builder.providers(readString(properties, "providers", builder.providers));
        builder.registryTimeoutMs(readInt(properties, "registry-timeout-ms", builder.registryTimeoutMs));
        builder.registrySessionTimeoutMs(readInt(properties, "registry-session-timeout-ms", builder.registrySessionTimeoutMs));
        builder.registryCheck(readBoolean(properties, "registry-check", builder.registryCheck));
        builder.protocol(readString(properties, "protocol", builder.protocol));
        builder.serialization(readString(properties, "serialization", builder.serialization));
        builder.timeoutMs(readInt(properties, "timeout-ms", builder.timeoutMs));
        builder.retries(readInt(properties, "retries", builder.retries));
        builder.check(readBoolean(properties, "check", builder.check));
        builder.lazy(readBoolean(properties, "lazy", builder.lazy));
        builder.connections(readInt(properties, "connections", builder.connections));
        builder.shareConnections(readInt(properties, "share-connections", builder.shareConnections));
        builder.referThreadNum(readInt(properties, "refer-thread-num", builder.referThreadNum));
        builder.maxInflight(readInt(properties, "max-inflight", builder.maxInflight));
        builder.maxResponseBytes(readInt(properties, "max-response-bytes", builder.maxResponseBytes));
        builder.nativeConnectionsPerEndpoint(readInt(properties, "native-connections-per-endpoint",
                builder.nativeConnectionsPerEndpoint));
        builder.nativeAsyncWorkers(readInt(properties, "native-async-workers", builder.nativeAsyncWorkers));
        builder.nativeAsyncQueueCapacity(readInt(properties, "native-async-queue-capacity",
                builder.nativeAsyncQueueCapacity));
        builder.runtimeProfile(readString(properties, "runtime-profile", builder.runtimeProfile));
        builder.transport(readString(properties, "transport", builder.transport));
        builder.cluster(readString(properties, "cluster", builder.cluster));
        builder.loadbalance(readString(properties, "loadbalance", builder.loadbalance));
        return builder.build();
    }

    public String applicationName() {
        return applicationName;
    }

    public String registryAddress() {
        return registryAddress;
    }

    public String registryRoot() {
        return registryRoot;
    }

    public String providers() {
        return providers;
    }

    public boolean staticProvidersEnabled() {
        return !providers.isEmpty();
    }

    public int registryTimeoutMs() {
        return registryTimeoutMs;
    }

    public int registrySessionTimeoutMs() {
        return registrySessionTimeoutMs;
    }

    public boolean registryCheck() {
        return registryCheck;
    }

    public String protocol() {
        return protocol;
    }

    public String serialization() {
        return serialization;
    }

    public int timeoutMs() {
        return timeoutMs;
    }

    public int retries() {
        return retries;
    }

    public boolean check() {
        return check;
    }

    public boolean lazy() {
        return lazy;
    }

    public int connections() {
        return connections;
    }

    public int shareConnections() {
        return shareConnections;
    }

    public int referThreadNum() {
        return referThreadNum;
    }

    public int maxInflight() {
        return maxInflight;
    }

    public int maxResponseBytes() {
        return maxResponseBytes;
    }

    public int nativeConnectionsPerEndpoint() {
        return nativeConnectionsPerEndpoint;
    }

    public int nativeAsyncWorkers() {
        return nativeAsyncWorkers;
    }

    public int nativeAsyncQueueCapacity() {
        return nativeAsyncQueueCapacity;
    }

    public String runtimeProfile() {
        return runtimeProfile;
    }

    public String transport() {
        return transport;
    }

    public String cluster() {
        return cluster;
    }

    public String loadbalance() {
        return loadbalance;
    }

    static String normalizeRegistryAddress(String address) {
        String trimmed = requireText(address, "registryAddress");
        if (trimmed.contains("://")) {
            return trimmed;
        }
        return "zookeeper://" + trimmed;
    }

    static String normalizeRegistryRoot(String root) {
        String value = requireText(root, "registryRoot");
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return requireText(value, "registryRoot");
    }

    static String normalizeProviders(String providers) {
        if (isBlank(providers)) {
            return "";
        }
        String[] tokens = providers.split("[,;\\n]");
        StringBuilder normalized = new StringBuilder(providers.length());
        for (String token : tokens) {
            String value = token.trim();
            if (value.isEmpty()) {
                continue;
            }
            if (normalized.length() > 0) {
                normalized.append(',');
            }
            normalized.append(value);
        }
        return normalized.toString();
    }

    private static String normalizeRuntimeProfile(String profile) {
        String value = requireText(profile, "runtimeProfile").toLowerCase(Locale.ROOT);
        if (!"low-rss".equals(value)
                && !RUNTIME_PROFILE_BALANCED_DUBBO.equals(value)
                && !"throughput".equals(value)
                && !"default".equals(value)) {
            throw new IllegalArgumentException(
                    "runtimeProfile must be low-rss, balanced-dubbo, throughput, or default");
        }
        return value;
    }

    private static String normalizeTransport(String transport) {
        String value = requireText(transport, "transport").toLowerCase(Locale.ROOT);
        if (!"native".equals(value) && !"official".equals(value)) {
            throw new IllegalArgumentException("transport must be native or official");
        }
        return value;
    }

    private static String readString(Properties properties, String key, String defaultValue) {
        String value = readRaw(properties, key);
        return isBlank(value) ? defaultValue : value.trim();
    }

    private static int readInt(Properties properties, String key, int defaultValue) {
        String value = readRaw(properties, key);
        if (isBlank(value)) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(PROPERTY_PREFIX + key + " must be an integer: " + value, e);
        }
    }

    private static boolean readBoolean(Properties properties, String key, boolean defaultValue) {
        String value = readRaw(properties, key);
        if (isBlank(value)) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value.trim());
    }

    private static String readRaw(Properties properties, String key) {
        String fullKey = PROPERTY_PREFIX + key;
        String system = System.getProperty(fullKey);
        if (!isBlank(system)) {
            return system;
        }
        String env = System.getenv(fullKey.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_'));
        if (!isBlank(env)) {
            return env;
        }
        return properties.getProperty(fullKey);
    }

    private static int requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static int requireNonNegative(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
        return value;
    }

    private static String requireText(String value, String name) {
        if (isBlank(value)) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public static final class Builder {
        private String applicationName = DEFAULT_APPLICATION_NAME;
        private String registryAddress = DEFAULT_REGISTRY_ADDRESS;
        private String registryRoot = DEFAULT_REGISTRY_ROOT;
        private String providers = "";
        private int registryTimeoutMs = 3_000;
        private int registrySessionTimeoutMs = 30_000;
        private boolean registryCheck;
        private String protocol = DEFAULT_PROTOCOL;
        private String serialization = DEFAULT_SERIALIZATION;
        private int timeoutMs = 1_000;
        private int retries;
        private boolean check;
        private boolean lazy;
        private int connections = 1;
        private int shareConnections = 1;
        private int referThreadNum = 1;
        private int maxInflight = 256;
        private int maxResponseBytes = 8 * 1024 * 1024;
        private int nativeConnectionsPerEndpoint = 16;
        private int nativeAsyncWorkers = 2;
        private int nativeAsyncQueueCapacity = 128;
        private String runtimeProfile = DEFAULT_RUNTIME_PROFILE;
        private String transport = DEFAULT_TRANSPORT;
        private String cluster = DEFAULT_CLUSTER;
        private String loadbalance = DEFAULT_LOADBALANCE;

        private Builder() {}

        public Builder applicationName(String applicationName) {
            this.applicationName = applicationName;
            return this;
        }

        public Builder registryAddress(String registryAddress) {
            this.registryAddress = registryAddress;
            return this;
        }

        public Builder registryRoot(String registryRoot) {
            this.registryRoot = registryRoot;
            return this;
        }

        public Builder providers(String providers) {
            this.providers = providers;
            return this;
        }

        public Builder registryTimeoutMs(int registryTimeoutMs) {
            this.registryTimeoutMs = registryTimeoutMs;
            return this;
        }

        public Builder registrySessionTimeoutMs(int registrySessionTimeoutMs) {
            this.registrySessionTimeoutMs = registrySessionTimeoutMs;
            return this;
        }

        public Builder registryCheck(boolean registryCheck) {
            this.registryCheck = registryCheck;
            return this;
        }

        public Builder protocol(String protocol) {
            this.protocol = protocol;
            return this;
        }

        public Builder serialization(String serialization) {
            this.serialization = serialization;
            return this;
        }

        public Builder timeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
            return this;
        }

        public Builder retries(int retries) {
            this.retries = retries;
            return this;
        }

        public Builder check(boolean check) {
            this.check = check;
            return this;
        }

        public Builder lazy(boolean lazy) {
            this.lazy = lazy;
            return this;
        }

        public Builder connections(int connections) {
            this.connections = connections;
            return this;
        }

        public Builder shareConnections(int shareConnections) {
            this.shareConnections = shareConnections;
            return this;
        }

        public Builder referThreadNum(int referThreadNum) {
            this.referThreadNum = referThreadNum;
            return this;
        }

        public Builder maxInflight(int maxInflight) {
            this.maxInflight = maxInflight;
            return this;
        }

        public Builder maxResponseBytes(int maxResponseBytes) {
            this.maxResponseBytes = maxResponseBytes;
            return this;
        }

        public Builder nativeConnectionsPerEndpoint(int nativeConnectionsPerEndpoint) {
            this.nativeConnectionsPerEndpoint = nativeConnectionsPerEndpoint;
            return this;
        }

        public Builder nativeAsyncWorkers(int nativeAsyncWorkers) {
            this.nativeAsyncWorkers = nativeAsyncWorkers;
            return this;
        }

        public Builder nativeAsyncQueueCapacity(int nativeAsyncQueueCapacity) {
            this.nativeAsyncQueueCapacity = nativeAsyncQueueCapacity;
            return this;
        }

        public Builder runtimeProfile(String runtimeProfile) {
            this.runtimeProfile = runtimeProfile;
            return this;
        }

        public Builder transport(String transport) {
            this.transport = transport;
            return this;
        }

        public Builder cluster(String cluster) {
            this.cluster = cluster;
            return this;
        }

        public Builder loadbalance(String loadbalance) {
            this.loadbalance = loadbalance;
            return this;
        }

        public DubboConsumerConfig build() {
            return new DubboConsumerConfig(this);
        }
    }
}
