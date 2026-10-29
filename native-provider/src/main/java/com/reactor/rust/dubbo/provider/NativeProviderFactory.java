package com.reactor.rust.dubbo.provider;

import com.reactor.rust.dubbo.runtime.*;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReferenceArray;

/** Explicit alternative to PlainDubboProvider. Uses the published shared engine, not rust-spring's acceptor. */
public final class NativeProviderFactory {
    private static final AtomicBoolean CLAIMED = new AtomicBoolean();
    private NativeProviderFactory() {}

    public record Options(int port, int ioWorkers, int businessWorkers, int queueCapacity,
                          int maxPayloadBytes, int requestTimeoutMs, int drainTimeoutMs, int maxCollectionItems) {
        public Options {
            if (port < 1 || port > 65535 || ioWorkers < 1 || ioWorkers > 8 || businessWorkers < 1 || businessWorkers > 64
                    || queueCapacity < 1 || queueCapacity > 1024 || maxPayloadBytes < 1024 || maxPayloadBytes > 1024 * 1024
                    || requestTimeoutMs < 1 || requestTimeoutMs > 60000 || drainTimeoutMs < 0 || drainTimeoutMs > 60000
                    || maxCollectionItems < 1 || maxCollectionItems > 4096) throw new IllegalArgumentException("Native provider options exceed fixed bounds");
            if ((long) (businessWorkers + queueCapacity) * maxPayloadBytes > 32L * 1024 * 1024)
                throw new IllegalArgumentException("Queued/active request payload budget exceeds 32 MiB");
        }
        public static Options defaults(int port) { return new Options(port, 1, 4, 64, 256 * 1024, 5000, 5000, 1024); }
    }

    /** Validate/decorate first. The native ABI has process-global registration: one start attempt per JVM. */
    public static Running start(Options options, List<NativeProviderBinding<?>> services,
                                ProviderDispatcherDecorator decorator) {
        Objects.requireNonNull(options, "options");
        services = List.copyOf(services);
        if (services.isEmpty() || services.size() > 64) throw new IllegalArgumentException("Need 1..64 services");
        var prepared = new NativeProviderBinding.Prepared<?>[services.size()];
        var keys = new HashSet<String>();
        int count = 0;
        for (int i = 0; i < services.size(); i++) {
            var service = services.get(i);
            if (!keys.add(service.contract.serviceType().getName() + '|' + service.contract.group() + '|' + service.contract.version()))
                throw new IllegalArgumentException("Duplicate service identity");
            count += service.methods.size();
            if (count > 1024) throw new IllegalArgumentException("At most 1024 registered methods");
            prepared[i] = service.prepare(decorator);
        }
        if (!CLAIMED.compareAndSet(false, true)) throw new IllegalStateException("Shared native provider registry is one-shot per JVM");
        NativeDubboRuntime runtime = new NativeDubboRuntime(DubboClientOptions.microDefaults(),
                new DubboRuntimeOptions(options.ioWorkers(), 1, 64, 256 * 1024));
        try {
            validateNativeAdmission(NativeDubboBridge.metricsJson());
            for (int i = 0; i < services.size(); i++) {
                var service = services.get(i);
                for (var method : service.methods) runtime.registerProviderMethod(i + 1, method.id(),
                        service.contract.serviceType().getName(), service.contract.group(), service.contract.version(),
                        method.name(), method.descriptor(), "public-native", options.businessWorkers() + options.queueCapacity());
            }
            Running running = new Running(runtime, prepared, options);
            runtime.startProvider(options.port(), options.ioWorkers(), options.businessWorkers(), options.queueCapacity(),
                    options.maxPayloadBytes(), options.requestTimeoutMs(), options.drainTimeoutMs(), running);
            if (!runtime.providerRunning()) throw new IllegalStateException("Native provider did not start");
            return running;
        } catch (RuntimeException | Error failure) {
            try { runtime.close(); } catch (RuntimeException | Error closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    /** Startup-only capability check: ABI 3 alone also identifies older, unbounded engines. */
    static void validateNativeAdmission(String metrics) {
        if (metrics == null || metrics.length() > 16384
                || !boundedNativeLimit(metrics, "providerWireBytesLimit", 32L * 1024 * 1024)
                || !boundedNativeLimit(metrics, "providerConnectionAdmissionLimit", 256)
                || !boundedNativeLimit(metrics, "providerResponseAdmissionLimit", 256)
                || !boundedNativeLimit(metrics, "providerReaderPolicyVersion", 1)
                || !boundedNativeLimit(metrics, "providerReaderDepthLimit", 64)
                || !boundedNativeLimit(metrics, "providerReaderWorkLimit", 65536)
                || !boundedNativeLimit(metrics, "providerReaderMetadataBytesLimit", 65536))
            throw new IllegalStateException("Native provider requires the bounded ABI-3 engine with wire/connection/response admission and reader-policy gauges; older ABI-3 libraries are not supported");
    }

    private static boolean boundedNativeLimit(String metrics, String name, long maximum) {
        String key = "\"" + name + "\":";
        int start = metrics.indexOf(key);
        if (start < 0 || metrics.indexOf(key, start + key.length()) >= 0) return false;
        start += key.length(); int end = start;
        while (end < metrics.length() && metrics.charAt(end) >= '0' && metrics.charAt(end) <= '9') end++;
        if (end == start || end == metrics.length() || metrics.charAt(end) != ',' && metrics.charAt(end) != '}') return false;
        try { long value = Long.parseLong(metrics.substring(start, end)); return value > 0 && value <= maximum; }
        catch (NumberFormatException invalid) { return false; }
    }

    public static final class Running implements NativeDubboDispatcher, AutoCloseable {
        private final NativeDubboRuntime runtime;
        private final NativeProviderBinding.Prepared<?>[] services;
        private final AtomicReferenceArray<Context> contexts;
        private final Options options;
        private final AtomicBoolean closing = new AtomicBoolean();

        private Running(NativeDubboRuntime runtime, NativeProviderBinding.Prepared<?>[] services, Options options) {
            this.runtime = runtime; this.services = services; this.options = options;
            contexts = new AtomicReferenceArray<>(options.businessWorkers());
            for (int i = 0; i < options.businessWorkers(); i++) contexts.set(i, new Context(i));
        }

        @Override public int dispatch(int serviceId, int methodId, ByteBuffer request, int length,
                                       long responseHandle, ByteBuffer response) {
            Context context = borrowContext();
            if (context == null) throw new RejectedExecutionException("Native codec context capacity exhausted");
            try {
                context.output.attach(response, responseHandle);
                try {
                    if (closing.get()) throw new RejectedExecutionException("Native provider is closing");
                    if (serviceId < 1 || serviceId > services.length) throw new DubboCodecException("Unknown service id");
                    var prepared = services[serviceId - 1];
                    var binding = prepared.binding();
                    if (methodId < 1 || methodId > binding.methods.size()) throw new DubboCodecException("Unknown method id");
                    context.input.attach(request, length, options.maxCollectionItems());
                    Object[] arguments = binding.contract.readArguments(methodId, context.input);
                    if (context.input.remaining() != 0) throw new DubboCodecException("Trailing argument bytes");
                    Object result = prepared.invoke(binding.methods.get(methodId - 1), arguments);
                    context.output.writeInt(result == null ? 2 : 1);
                    if (result != null) binding.contract.writeResult(methodId, context.output, result);
                } catch (Exception failure) {
                    context.output.rewind(0);
                    context.output.writeInt(0);
                    context.output.writeRemoteThrowable(failure);
                } catch (Throwable failure) {
                    if (failure instanceof Error fatal) throw fatal;
                    context.output.rewind(0); context.output.writeInt(0); context.output.writeRemoteThrowable(failure);
                }
                return context.output.position();
            } finally {
                context.input.detach(); context.output.detach();
                if (!contexts.compareAndSet(context.slot, null, context)) throw new IllegalStateException("Codec context released twice");
            }
        }

        private Context borrowContext() {
            // Each slot gets one CAS attempt: fixed work, no blocking lock, spin or wait.
            for (int i = 0; i < contexts.length(); i++) {
                Context context = contexts.get(i);
                if (context != null && contexts.compareAndSet(i, context, null)) return context;
            }
            return null;
        }

        public boolean isClosing() { return closing.get(); }
        /** Approximate concurrent gauge; includes callbacks completing after a drain deadline. */
        public int activeCodecContexts() {
            int active = 0;
            for (int i = 0; i < contexts.length(); i++) if (contexts.get(i) == null) active++;
            return active;
        }
        @Override public void close() {
            closing.set(true);
            // stopProvider drains for the configured deadline. Never revoke buffers held by a late callback.
            runtime.close();
        }
        private static final class Context {
            final int slot;
            final Hessian2Input input = new Hessian2Input();
            final Hessian2Output output = new Hessian2Output();
            Context(int slot) { this.slot = slot; }
        }
    }
}
