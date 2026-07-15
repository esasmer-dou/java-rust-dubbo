package com.reactor.rust.dubbo.provider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Declarative lifecycle for the minimal Dubbo provider runtime.
 */
public final class DubboProviderApplication {

    private DubboProviderApplication() {}

    public static Builder builder() {
        return new Builder(DubboProviderSupport.fromProperties());
    }

    public static Builder builder(Properties properties) {
        return new Builder(DubboProviderSupport.fromProperties(properties));
    }

    public static Builder builder(com.reactor.rust.dubbo.config.DubboApplicationProperties properties) {
        return builder(Objects.requireNonNull(properties, "properties").asProperties());
    }

    @FunctionalInterface
    public interface StartupAction {
        void run() throws Exception;
    }

    @FunctionalInterface
    public interface Module {
        void configure(ModuleContext context) throws Exception;
    }

    public static final class ModuleContext {

        private final Builder builder;
        private final ResourceStack resources;

        private ModuleContext(Builder builder, ResourceStack resources) {
            this.builder = builder;
            this.resources = resources;
        }

        public <T extends AutoCloseable> T manage(T resource) {
            T managed = Objects.requireNonNull(resource, "resource");
            resources.add(managed);
            return managed;
        }

        public <T> ModuleContext service(Class<T> serviceType, T implementation) {
            builder.service(serviceType, implementation);
            return this;
        }

        public ModuleContext onStart(StartupAction action) {
            builder.onStart(action);
            return this;
        }

        public ModuleContext onStartIf(boolean condition, StartupAction action) {
            builder.onStartIf(condition, action);
            return this;
        }
    }

    public static final class Builder {

        private final DubboProviderSupport support;
        private final Map<Class<?>, DubboProviderSupport.ServicePlan<?>> services = new LinkedHashMap<>();
        private final List<AutoCloseable> managedResources = new ArrayList<>();
        private final List<StartupAction> startupActions = new ArrayList<>();
        private final List<Module> modules = new ArrayList<>();
        private String name = "provider";
        private String shutdownThreadName = "dubbo-provider-shutdown";
        private boolean registryEnabled;
        private boolean started;

        private Builder(DubboProviderSupport support) {
            this.support = support;
        }

        public Builder name(String name) {
            this.name = requireText(name, "name");
            return this;
        }

        public Builder registryEnabled(boolean registryEnabled) {
            this.registryEnabled = registryEnabled;
            return this;
        }

        public Builder shutdownThreadName(String shutdownThreadName) {
            this.shutdownThreadName = requireText(shutdownThreadName, "shutdownThreadName");
            return this;
        }

        public <T> Builder service(Class<T> serviceType, T implementation) {
            Objects.requireNonNull(serviceType, "serviceType");
            Objects.requireNonNull(implementation, "implementation");
            if (services.putIfAbsent(serviceType, support.service(serviceType, implementation)) != null) {
                throw new IllegalArgumentException("Dubbo service already declared: " + serviceType.getName());
            }
            return this;
        }

        public Builder manage(AutoCloseable... resources) {
            if (resources != null) {
                for (AutoCloseable resource : resources) {
                    managedResources.add(Objects.requireNonNull(resource, "resource"));
                }
            }
            return this;
        }

        public Builder onStart(StartupAction action) {
            startupActions.add(Objects.requireNonNull(action, "action"));
            return this;
        }

        public Builder onStartIf(boolean condition, StartupAction action) {
            if (condition) {
                onStart(action);
            }
            return this;
        }

        public Builder module(Module module) {
            modules.add(Objects.requireNonNull(module, "module"));
            return this;
        }

        public void run() throws Exception {
            try (RunningProvider provider = start()) {
                try {
                    provider.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        public synchronized RunningProvider start() throws Exception {
            if (started) {
                throw new IllegalStateException("Dubbo provider application builder can only start once");
            }
            started = true;

            ResourceStack resources = new ResourceStack();
            managedResources.forEach(resources::add);
            try {
                ModuleContext context = new ModuleContext(this, resources);
                for (Module module : modules) {
                    module.configure(context);
                }
                if (services.isEmpty()) {
                    throw new IllegalStateException("At least one Dubbo service must be declared");
                }
                for (StartupAction action : startupActions) {
                    action.run();
                }

                PlainDubboProvider.ProviderConfig config = support.providerConfig(registryEnabled);
                DubboProviderRegistration registration = support.providerRegistration(registryEnabled);
                resources.add(registration);

                List<DubboProviderSupport.ExportedService<?>> exported = support.exportAll(
                        config,
                        registration,
                        List.copyOf(services.values()));
                exported.forEach(resources::add);
                support.logStartup(name, config, registryEnabled, exported);

                return RunningProvider.start(shutdownThreadName, resources);
            } catch (Exception | Error startupFailure) {
                resources.closeAfterFailure(startupFailure);
                throw startupFailure;
            }
        }
    }

    public static final class RunningProvider implements AutoCloseable {

        private final ResourceStack resources;
        private final CountDownLatch stopped = new CountDownLatch(1);
        private final AtomicBoolean stopping = new AtomicBoolean();
        private final Thread shutdownHook;

        private RunningProvider(String shutdownThreadName, ResourceStack resources) {
            this.resources = resources;
            this.shutdownHook = new Thread(() -> shutdown(false), shutdownThreadName);
        }

        private static RunningProvider start(String shutdownThreadName, ResourceStack resources) {
            RunningProvider provider = new RunningProvider(shutdownThreadName, resources);
            Runtime.getRuntime().addShutdownHook(provider.shutdownHook);
            return provider;
        }

        public void await() throws InterruptedException {
            stopped.await();
        }

        @Override
        public void close() {
            shutdown(true);
        }

        private void shutdown(boolean removeHook) {
            if (!stopping.compareAndSet(false, true)) {
                awaitStoppedUninterruptibly();
                return;
            }
            if (removeHook) {
                removeShutdownHook();
            }
            try {
                resources.close();
            } finally {
                stopped.countDown();
            }
        }

        private void removeShutdownHook() {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // JVM shutdown is already running; the hook owns cleanup.
            }
        }

        private void awaitStoppedUninterruptibly() {
            boolean interrupted = false;
            while (stopped.getCount() != 0) {
                try {
                    stopped.await();
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static final class ResourceStack implements AutoCloseable {

        private final List<AutoCloseable> resources = new ArrayList<>();
        private boolean closed;

        synchronized void add(AutoCloseable resource) {
            if (resource != null) {
                resources.add(resource);
            }
        }

        void closeAfterFailure(Throwable startupFailure) {
            try {
                close();
            } catch (RuntimeException closeFailure) {
                startupFailure.addSuppressed(closeFailure);
            }
        }

        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            Throwable failure = null;
            for (int index = resources.size() - 1; index >= 0; index--) {
                try {
                    resources.get(index).close();
                } catch (Exception | LinkageError closeFailure) {
                    if (failure == null) {
                        failure = closeFailure;
                    } else {
                        failure.addSuppressed(closeFailure);
                    }
                }
            }
            resources.clear();
            if (failure instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            if (failure != null) {
                throw new IllegalStateException("Failed to close Dubbo provider resources", failure);
            }
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
