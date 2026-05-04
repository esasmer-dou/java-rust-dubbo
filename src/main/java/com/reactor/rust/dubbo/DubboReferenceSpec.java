package com.reactor.rust.dubbo;

import java.util.Objects;

public final class DubboReferenceSpec<T> {

    private final Class<T> serviceInterface;
    private final String group;
    private final String version;
    private final String protocol;
    private final String serialization;
    private final String cluster;
    private final String loadbalance;
    private final Integer timeoutMs;
    private final Integer retries;
    private final Boolean check;
    private final Boolean lazy;
    private final Integer connections;

    private DubboReferenceSpec(Builder<T> builder) {
        this.serviceInterface = Objects.requireNonNull(builder.serviceInterface, "serviceInterface");
        if (!this.serviceInterface.isInterface()) {
            throw new IllegalArgumentException("Dubbo reference type must be an interface: " + this.serviceInterface.getName());
        }
        this.group = blankToNull(builder.group);
        this.version = blankToNull(builder.version);
        this.protocol = blankToNull(builder.protocol);
        this.serialization = blankToNull(builder.serialization);
        this.cluster = blankToNull(builder.cluster);
        this.loadbalance = blankToNull(builder.loadbalance);
        this.timeoutMs = positiveOrNull(builder.timeoutMs, "timeoutMs");
        this.retries = nonNegativeOrNull(builder.retries, "retries");
        this.check = builder.check;
        this.lazy = builder.lazy;
        this.connections = nonNegativeOrNull(builder.connections, "connections");
    }

    public static <T> Builder<T> builder(Class<T> serviceInterface) {
        return new Builder<>(serviceInterface);
    }

    public static <T> DubboReferenceSpec<T> of(Class<T> serviceInterface) {
        return builder(serviceInterface).build();
    }

    public Class<T> serviceInterface() {
        return serviceInterface;
    }

    public String group() {
        return group;
    }

    public String version() {
        return version;
    }

    public String protocol() {
        return protocol;
    }

    public String serialization() {
        return serialization;
    }

    public String cluster() {
        return cluster;
    }

    public String loadbalance() {
        return loadbalance;
    }

    public Integer timeoutMs() {
        return timeoutMs;
    }

    public Integer retries() {
        return retries;
    }

    public Boolean check() {
        return check;
    }

    public Boolean lazy() {
        return lazy;
    }

    public Integer connections() {
        return connections;
    }

    static <T> DubboReferenceSpec<T> fromAnnotation(Class<T> serviceInterface, DubboReference annotation) {
        Builder<T> builder = builder(serviceInterface)
                .group(annotation.group())
                .version(annotation.version())
                .protocol(annotation.protocol())
                .serialization(annotation.serialization())
                .cluster(annotation.cluster())
                .loadbalance(annotation.loadbalance());
        if (annotation.timeoutMs() >= 0) {
            builder.timeoutMs(annotation.timeoutMs());
        }
        if (annotation.retries() >= 0) {
            builder.retries(annotation.retries());
        }
        if (annotation.connections() >= 0) {
            builder.connections(annotation.connections());
        }
        if (annotation.check()) {
            builder.check(true);
        }
        if (annotation.lazy()) {
            builder.lazy(true);
        }
        return builder.build();
    }

    private static Integer positiveOrNull(Integer value, String name) {
        if (value == null) {
            return null;
        }
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static Integer nonNegativeOrNull(Integer value, String name) {
        if (value == null) {
            return null;
        }
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
        return value;
    }

    private static String blankToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    public static final class Builder<T> {
        private final Class<T> serviceInterface;
        private String group;
        private String version;
        private String protocol;
        private String serialization;
        private String cluster;
        private String loadbalance;
        private Integer timeoutMs;
        private Integer retries;
        private Boolean check;
        private Boolean lazy;
        private Integer connections;

        private Builder(Class<T> serviceInterface) {
            this.serviceInterface = serviceInterface;
        }

        public Builder<T> group(String group) {
            this.group = group;
            return this;
        }

        public Builder<T> version(String version) {
            this.version = version;
            return this;
        }

        public Builder<T> protocol(String protocol) {
            this.protocol = protocol;
            return this;
        }

        public Builder<T> serialization(String serialization) {
            this.serialization = serialization;
            return this;
        }

        public Builder<T> cluster(String cluster) {
            this.cluster = cluster;
            return this;
        }

        public Builder<T> loadbalance(String loadbalance) {
            this.loadbalance = loadbalance;
            return this;
        }

        public Builder<T> timeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
            return this;
        }

        public Builder<T> retries(int retries) {
            this.retries = retries;
            return this;
        }

        public Builder<T> check(boolean check) {
            this.check = check;
            return this;
        }

        public Builder<T> lazy(boolean lazy) {
            this.lazy = lazy;
            return this;
        }

        public Builder<T> connections(int connections) {
            this.connections = connections;
            return this;
        }

        public DubboReferenceSpec<T> build() {
            return new DubboReferenceSpec<>(this);
        }
    }
}
