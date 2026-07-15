package com.reactor.rust.dubbo.provider;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;

public final class DubboProviderSupport {

    private final Properties properties;

    private DubboProviderSupport(Properties properties) {
        this.properties = copy(properties);
    }

    public static DubboProviderSupport fromProperties() {
        return new DubboProviderSupport(new Properties());
    }

    public static DubboProviderSupport fromProperties(Properties properties) {
        return new DubboProviderSupport(properties);
    }

    public PlainDubboProvider.ProviderConfig providerConfig(boolean registryEnabled) {
        return new PlainDubboProvider.ProviderConfig(
                get("dubbo.provider.application-name"),
                registryEnabled ? get("reactor.dubbo.registry-address") : "",
                registryEnabled ? get("reactor.dubbo.registry-root") : "",
                get("dubbo.provider.host"),
                get("dubbo.provider.bind-host"),
                getInt("dubbo.provider.port")
        );
    }

    public DubboProviderRegistration providerRegistration(boolean registryEnabled) throws Exception {
        if (!registryEnabled) {
            return DubboProviderRegistration.disabled();
        }
        PlainDubboProvider.ProviderConfig provider = providerConfig(true);
        ZookeeperDubboProviderRegistration.RegistryConfig registry =
                new ZookeeperDubboProviderRegistration.RegistryConfig(
                        provider.registryAddress(),
                        provider.registryRoot(),
                        provider.applicationName(),
                        getIntOrDefault("reactor.dubbo.registry-timeout-ms", 5_000),
                        getIntOrDefault("reactor.dubbo.registry-session-timeout-ms", 30_000),
                        getIntOrDefault("reactor.dubbo.registry-reconnect-initial-delay-ms", 250),
                        getIntOrDefault("reactor.dubbo.registry-reconnect-max-delay-ms", 10_000),
                        getOrDefault("reactor.dubbo.registry-auth-scheme", ""),
                        getOrDefault("reactor.dubbo.registry-auth-data", ""),
                        ZookeeperDubboProviderRegistration.AclMode.parse(
                                getOrDefault("reactor.dubbo.registry-acl", "auto")));
        return ZookeeperDubboProviderRegistration.open(registry);
    }

    public <T> ServicePlan<T> service(Class<T> serviceType, T implementation) {
        return new ServicePlan<>(serviceType, implementation, serviceExecutionConfig(serviceType));
    }

    public List<ExportedService<?>> exportAll(
            PlainDubboProvider.ProviderConfig config,
            DubboProviderRegistration registration,
            List<ServicePlan<?>> services) throws Exception {
        List<ExportedService<?>> exported = new ArrayList<>(services.size());
        try {
            for (ServicePlan<?> service : services) {
                exported.add(exportOne(service, config, registration));
            }
            return Collections.unmodifiableList(exported);
        } catch (Exception e) {
            closeAll(exported);
            throw e;
        }
    }

    public void awaitShutdown(
            String shutdownThreadName,
            List<ExportedService<?>> exported,
            AutoCloseable... closeables) throws InterruptedException {
        CountDownLatch stop = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            closeAll(exported);
            closeAll(closeables);
            stop.countDown();
        }, shutdownThreadName));
        stop.await();
    }

    public void logStartup(
            String label,
            PlainDubboProvider.ProviderConfig config,
            boolean registryEnabled,
            List<ExportedService<?>> exported) {
        for (ExportedService<?> service : exported) {
            System.out.println("[java-rust-dubbo-provider] " + label + " exported "
                    + service.provider().url().toFullString());
        }
        System.out.println("[java-rust-dubbo-provider] execution limits " + executionSummary(exported));
        if (registryEnabled) {
            System.out.println("[java-rust-dubbo-provider] registered at "
                    + config.registryAddress() + "/" + config.registryRoot());
        } else {
            System.out.println("[java-rust-dubbo-provider] registry disabled; static consumers can use "
                    + config.host() + ":" + config.port());
        }
    }

    public void closeAll(List<? extends AutoCloseable> closeables) {
        for (int i = closeables.size() - 1; i >= 0; i--) {
            closeQuietly(closeables.get(i));
        }
    }

    public void closeAll(AutoCloseable... closeables) {
        for (AutoCloseable closeable : closeables) {
            closeQuietly(closeable);
        }
    }

    private <T> ExportedService<T> exportOne(
            ServicePlan<T> service,
            PlainDubboProvider.ProviderConfig config,
            DubboProviderRegistration registration) throws Exception {
        PlainDubboProvider<T> provider = PlainDubboProvider.export(
                service.serviceType(),
                service.implementation(),
                config,
                registration,
                service.executionConfig()
        );
        return new ExportedService<>(service.serviceType(), provider, service.executionConfig());
    }

    private PlainDubboProvider.ServiceExecutionConfig serviceExecutionConfig(Class<?> serviceType) {
        int defaultMax = getInt("dubbo.provider.service.default.max-concurrent");
        int max = getIntOrDefault(
                "dubbo.provider.service." + serviceType.getName() + ".max-concurrent",
                getIntOrDefault(
                        "dubbo.provider.service." + serviceType.getSimpleName() + ".max-concurrent",
                        defaultMax
                )
        );
        return PlainDubboProvider.ServiceExecutionConfig.bounded(max, methodExecutionOverrides(serviceType));
    }

    private Map<String, Integer> methodExecutionOverrides(Class<?> serviceType) {
        Map<String, Integer> overrides = new LinkedHashMap<>();
        for (Method method : serviceType.getMethods()) {
            if (method.getDeclaringClass() == Object.class) {
                continue;
            }
            Integer max = methodMaxConcurrent(serviceType, method.getName());
            if (max != null) {
                overrides.put(method.getName(), max);
            }
        }
        return overrides;
    }

    private Integer methodMaxConcurrent(Class<?> serviceType, String methodName) {
        String fqcnKey = "dubbo.provider.service." + serviceType.getName()
                + ".method." + methodName + ".max-concurrent";
        String simpleKey = "dubbo.provider.service." + serviceType.getSimpleName()
                + ".method." + methodName + ".max-concurrent";
        String value = getOrDefault(fqcnKey, getOrDefault(simpleKey, ""));
        return value.isBlank() ? null : parsePositiveInt(fqcnKey + " / " + simpleKey, value);
    }

    private String get(String key) {
        String value = getOrDefault(key, "");
        if (value.isBlank()) {
            throw new IllegalStateException("Missing required provider property: " + key);
        }
        return value;
    }

    private String getOrDefault(String key, String defaultValue) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            value = System.getenv(toEnvKey(key));
        }
        if (value == null || value.isBlank()) {
            value = properties.getProperty(key);
        }
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private int getInt(String key) {
        return parseInt(key, get(key));
    }

    private int getIntOrDefault(String key, int defaultValue) {
        String value = getOrDefault(key, "");
        return value.isBlank() ? defaultValue : parseInt(key, value);
    }

    private static int parseInt(String key, String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Provider property must be an integer: " + key + "=" + value, e);
        }
    }

    private static int parsePositiveInt(String key, String value) {
        int parsed = parseInt(key, value);
        if (parsed < 1) {
            throw new IllegalArgumentException("Provider property must be >= 1: " + key + "=" + value);
        }
        return parsed;
    }

    private static String executionSummary(List<ExportedService<?>> exported) {
        List<String> values = new ArrayList<>(exported.size());
        for (ExportedService<?> service : exported) {
            values.add(formatExecution(service.serviceType(), service.executionConfig()));
        }
        return String.join(", ", values);
    }

    private static String formatExecution(
            Class<?> serviceType,
            PlainDubboProvider.ServiceExecutionConfig config) {
        String value = serviceType.getSimpleName() + "=" + config.maxConcurrentInvocations();
        if (config.hasMethodOverrides()) {
            value += " methods=" + config.methodMaxConcurrentInvocations();
        }
        return value;
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Startup failure and shutdown cleanup are best effort.
        }
    }

    private static Properties copy(Properties properties) {
        Objects.requireNonNull(properties, "properties");
        Properties copy = new Properties();
        copy.putAll(properties);
        return copy;
    }

    private static String toEnvKey(String key) {
        return key.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
    }

    public record ServicePlan<T>(
            Class<T> serviceType,
            T implementation,
            PlainDubboProvider.ServiceExecutionConfig executionConfig) {}

    public record ExportedService<T>(
            Class<T> serviceType,
            PlainDubboProvider<T> provider,
            PlainDubboProvider.ServiceExecutionConfig executionConfig) implements AutoCloseable {

        @Override
        public void close() {
            provider.close();
        }
    }
}
