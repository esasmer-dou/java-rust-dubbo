package com.reactor.rust.dubbo.provider;

import com.reactor.rust.dubbo.internal.runtime.DubboRuntimeModel;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.rpc.Exporter;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.model.ModuleModel;
import org.apache.dubbo.rpc.model.ServiceDescriptor;
import org.apache.dubbo.rpc.model.ServiceMetadata;
import org.apache.dubbo.rpc.protocol.PermittedSerializationKeeper;
import org.apache.dubbo.rpc.proxy.AbstractProxyInvoker;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

public final class PlainDubboProvider<T> implements AutoCloseable {

    private static final String DEFAULT_DUBBO_VERSION = "0.0.0";

    private final Exporter<T> exporter;
    private PlainDubboProvider(Exporter<T> exporter) {
        this.exporter = exporter;
    }

    public static <T> PlainDubboProvider<T> export(
            Class<T> serviceType,
            T service,
            ProviderConfig config
    ) throws Exception {
        return export(serviceType, service, config, ServiceExecutionConfig.unbounded());
    }

    public static <T> PlainDubboProvider<T> export(
            Class<T> serviceType,
            T service,
            ProviderConfig config,
            ServiceExecutionConfig executionConfig
    ) throws Exception {
        return export(serviceType, service, config, null, executionConfig);
    }

    public static <T> PlainDubboProvider<T> export(
            Class<T> serviceType,
            T service,
            ProviderConfig config,
            DubboProviderRegistration sharedRegistration
    ) throws Exception {
        return export(serviceType, service, config, sharedRegistration, ServiceExecutionConfig.unbounded());
    }

    public static <T> PlainDubboProvider<T> export(
            Class<T> serviceType,
            T service,
            ProviderConfig config,
            DubboProviderRegistration sharedRegistration,
            ServiceExecutionConfig executionConfig
    ) throws Exception {
        return export(
                serviceType,
                service,
                config,
                sharedRegistration,
                executionConfig,
                MethodHandleProviderDispatcher.create(serviceType, service));
    }

    public static <T> PlainDubboProvider<T> export(
            Class<T> serviceType,
            T service,
            ProviderConfig config,
            DubboProviderRegistration sharedRegistration,
            ServiceExecutionConfig executionConfig,
            ProviderMethodDispatcher<T> dispatcher
    ) throws Exception {
        URL exportUrl = providerUrl(serviceType, config, executionConfig);
        registerServiceModel(serviceType, service, exportUrl);
        registerPermittedSerialization(exportUrl);
        Invoker<T> invoker = new DispatchingInvoker<>(
                service,
                serviceType,
                exportUrl,
                executionConfig,
                Objects.requireNonNull(dispatcher, "dispatcher"));

        ExtensionLoader<Protocol> loader = DubboRuntimeModel.module().getExtensionLoader(Protocol.class);
        Protocol protocol = loader.getExtension("dubbo", false);
        Exporter<T> exporter = protocol.export(invoker);

        try {
            if (sharedRegistration != null) {
                sharedRegistration.register(serviceType, exportUrl);
            }
            return new PlainDubboProvider<>(exporter);
        } catch (Exception e) {
            exporter.unexport();
            throw e;
        }
    }

    public URL url() {
        return exporter.getInvoker().getUrl();
    }

    @Override
    public void close() {
        exporter.unexport();
    }

    private static <T> URL providerUrl(
            Class<T> serviceType,
            ProviderConfig config,
            ServiceExecutionConfig executionConfig) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("application", config.applicationName());
        parameters.put("interface", serviceType.getName());
        parameters.put("version", DEFAULT_DUBBO_VERSION);
        parameters.put("side", "provider");
        parameters.put("category", "providers");
        parameters.put("serialization", "hessian2");
        parameters.put("methods", methodNames(serviceType));
        parameters.put("timestamp", Long.toString(System.currentTimeMillis()));
        parameters.put("pid", currentPid());
        parameters.put("anyhost", Boolean.toString("0.0.0.0".equals(config.bindHost())));
        parameters.put("bind.ip", config.bindHost());
        parameters.put("bind.port", Integer.toString(config.port()));
        parameters.put("dubbo", "3.3.5");
        if (executionConfig.isBounded()) {
            parameters.put("executes", Integer.toString(executionConfig.maxConcurrentInvocations()));
            parameters.put("reactor.max-concurrent", Integer.toString(executionConfig.maxConcurrentInvocations()));
            if (!executionConfig.methodMaxConcurrentInvocations().isEmpty()) {
                parameters.put(
                        "reactor.method-max-concurrent",
                        methodLimitMetadata(executionConfig.methodMaxConcurrentInvocations()));
            }
        }

        return new URL("dubbo", config.host(), config.port(), serviceType.getName(), parameters)
                .setScopeModel(DubboRuntimeModel.module());
    }

    private static <T> void registerServiceModel(Class<T> serviceType, T service, URL exportUrl) {
        ModuleModel module = DubboRuntimeModel.module();
        ServiceDescriptor descriptor = module.getServiceRepository().registerService(serviceType);
        ServiceMetadata metadata = new ServiceMetadata();
        metadata.setServiceType(serviceType);
        metadata.setTarget(service);
        module.getServiceRepository().registerProvider(exportUrl.getServiceKey(), service, descriptor, null, metadata);
    }

    private static void registerPermittedSerialization(URL exportUrl) {
        DubboRuntimeModel.module()
                .getApplicationModel()
                .getFrameworkModel()
                .getBeanFactory()
                .getOrRegisterBean(PermittedSerializationKeeper.class)
                .registerService(exportUrl);
    }

    private static String methodNames(Class<?> serviceType) {
        TreeSet<String> names = new TreeSet<>();
        for (Method method : serviceType.getMethods()) {
            if (method.getDeclaringClass() != Object.class) {
                names.add(method.getName());
            }
        }
        return String.join(",", names);
    }

    private static String currentPid() {
        String name = ManagementFactory.getRuntimeMXBean().getName();
        int at = name.indexOf('@');
        return at > 0 ? name.substring(0, at) : name;
    }

    private static String methodLimitMetadata(Map<String, Integer> methodLimits) {
        TreeSet<String> entries = new TreeSet<>();
        for (Map.Entry<String, Integer> entry : methodLimits.entrySet()) {
            entries.add(entry.getKey() + ":" + entry.getValue());
        }
        return String.join(",", entries);
    }

    private static final class DispatchingInvoker<T> extends AbstractProxyInvoker<T> {

        private final ProviderConcurrencyGate concurrencyGate;
        private final ProviderMethodDispatcher<T> dispatcher;

        private DispatchingInvoker(
                T proxy,
                Class<T> type,
                URL url,
                ServiceExecutionConfig executionConfig,
                ProviderMethodDispatcher<T> dispatcher) {
            super(proxy, type, url);
            this.concurrencyGate = ProviderConcurrencyGate.forService(type, executionConfig);
            this.dispatcher = dispatcher;
        }

        @Override
        protected Object doInvoke(T proxy, String methodName, Class<?>[] parameterTypes, Object[] arguments)
                throws Throwable {
            ProviderConcurrencyGate.MethodGate methodGate = concurrencyGate.acquireOrReject(methodName);
            try {
                return dispatcher.invoke(proxy, methodName, parameterTypes, arguments);
            } finally {
                concurrencyGate.release(methodGate);
            }
        }
    }

    public record ProviderConfig(
            String applicationName,
            String registryAddress,
            String registryRoot,
            String host,
            String bindHost,
            int port) {}

    public record ServiceExecutionConfig(
            int maxConcurrentInvocations,
            Map<String, Integer> methodMaxConcurrentInvocations) {

        public ServiceExecutionConfig {
            if (maxConcurrentInvocations < 1) {
                throw new IllegalArgumentException("maxConcurrentInvocations must be >= 1");
            }
            methodMaxConcurrentInvocations = methodMaxConcurrentInvocations == null
                    ? Map.of()
                    : Map.copyOf(methodMaxConcurrentInvocations);
            for (Map.Entry<String, Integer> entry : methodMaxConcurrentInvocations.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank()) {
                    throw new IllegalArgumentException("method limit name must not be blank");
                }
                if (entry.getValue() == null || entry.getValue() < 1) {
                    throw new IllegalArgumentException("method maxConcurrentInvocations must be >= 1 for "
                            + entry.getKey());
                }
            }
        }

        public static ServiceExecutionConfig bounded(int maxConcurrentInvocations) {
            return new ServiceExecutionConfig(maxConcurrentInvocations, Map.of());
        }

        public static ServiceExecutionConfig bounded(
                int maxConcurrentInvocations,
                Map<String, Integer> methodMaxConcurrentInvocations) {
            return new ServiceExecutionConfig(maxConcurrentInvocations, methodMaxConcurrentInvocations);
        }

        public static ServiceExecutionConfig unbounded() {
            return new ServiceExecutionConfig(Integer.MAX_VALUE, Map.of());
        }

        public boolean hasMethodOverrides() {
            return !methodMaxConcurrentInvocations.isEmpty();
        }

        boolean isBounded() {
            return maxConcurrentInvocations != Integer.MAX_VALUE;
        }
    }
}
