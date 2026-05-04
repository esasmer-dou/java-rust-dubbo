package com.reactor.rust.dubbo;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class NativeDubboBridge {

    private static final AtomicLong CALLBACK_IDS = new AtomicLong(1);
    private static final ConcurrentHashMap<Long, CompletableFuture<byte[]>> PENDING = new ConcurrentHashMap<>();

    private NativeDubboBridge() {}

    static {
        loadNativeLibrary();
    }

    static int createClient(int timeoutMs, int maxInflight, int maxResponseBytes, int maxConnectionsPerEndpoint) {
        int id = nativeCreateClient(timeoutMs, maxInflight, maxResponseBytes, maxConnectionsPerEndpoint);
        if (id <= 0) {
            throw new DubboConsumerException("Failed to create native Dubbo client");
        }
        return id;
    }

    static int updateProviders(int clientId, String providers) {
        return nativeUpdateProviders(clientId, providers == null ? "" : providers);
    }

    static void configureAsync(int workers, int queueCapacity) {
        nativeConfigureAsync(workers, queueCapacity);
    }

    static byte[] invoke(int clientId, byte[] requestBody, int timeoutMs) {
        return nativeInvoke(clientId, requestBody, timeoutMs);
    }

    static CompletableFuture<byte[]> invokeAsync(int clientId, byte[] requestBody, int timeoutMs) {
        long callbackId = CALLBACK_IDS.getAndIncrement();
        CompletableFuture<byte[]> future = new CompletableFuture<>();
        PENDING.put(callbackId, future);
        boolean accepted = nativeInvokeAsync(clientId, requestBody, timeoutMs, callbackId);
        if (!accepted) {
            PENDING.remove(callbackId);
            future.completeExceptionally(new DubboConsumerException("Native Dubbo async queue rejected the call"));
        }
        return future;
    }

    @SuppressWarnings("unchecked")
    static <R> R invokeByteArrayNoArgs(
            int clientId,
            String serviceName,
            String group,
            String version,
            String methodName,
            int timeoutMs) {
        return (R) nativeInvokeByteArrayNoArgs(clientId, serviceName, group, version, methodName, timeoutMs);
    }

    static CompletableFuture<byte[]> invokeByteArrayNoArgsAsync(
            int clientId,
            String serviceName,
            String group,
            String version,
            String methodName,
            int timeoutMs) {
        long callbackId = CALLBACK_IDS.getAndIncrement();
        CompletableFuture<byte[]> future = new CompletableFuture<>();
        PENDING.put(callbackId, future);
        boolean accepted = nativeInvokeByteArrayNoArgsAsync(
                clientId,
                serviceName,
                group,
                version,
                methodName,
                timeoutMs,
                callbackId);
        if (!accepted) {
            PENDING.remove(callbackId);
            future.completeExceptionally(new DubboConsumerException("Native Dubbo async queue rejected the call"));
        }
        return future;
    }

    static void closeClient(int clientId) {
        nativeCloseClient(clientId);
    }

    public static String metricsJson() {
        return nativeMetricsJson();
    }

    public static void resetMetrics() {
        nativeResetMetrics();
    }

    private static void loadNativeLibrary() {
        try {
            Class.forName("com.reactor.rust.bridge.NativeBridge", true,
                    Thread.currentThread().getContextClassLoader());
            return;
        } catch (ClassNotFoundException ignored) {
            // Standalone adapter smoke tests may load by java.library.path.
        }
        System.loadLibrary("rust_hyper");
    }

    private static void completeInvoke(long callbackId, byte[] responseBody, String errorMessage) {
        CompletableFuture<byte[]> future = PENDING.remove(callbackId);
        if (future == null) {
            return;
        }
        if (errorMessage == null) {
            future.complete(responseBody);
        } else {
            future.completeExceptionally(new DubboConsumerException(errorMessage));
        }
    }

    private static native int nativeCreateClient(
            int timeoutMs,
            int maxInflight,
            int maxResponseBytes,
            int maxConnectionsPerEndpoint);

    private static native int nativeUpdateProviders(int clientId, String providers);

    private static native void nativeConfigureAsync(int workers, int queueCapacity);

    private static native byte[] nativeInvoke(int clientId, byte[] requestBody, int timeoutMs);

    private static native boolean nativeInvokeAsync(int clientId, byte[] requestBody, int timeoutMs, long callbackId);

    private static native byte[] nativeInvokeByteArrayNoArgs(
            int clientId,
            String serviceName,
            String group,
            String version,
            String methodName,
            int timeoutMs);

    private static native boolean nativeInvokeByteArrayNoArgsAsync(
            int clientId,
            String serviceName,
            String group,
            String version,
            String methodName,
            int timeoutMs,
            long callbackId);

    private static native void nativeCloseClient(int clientId);

    private static native String nativeMetricsJson();

    private static native void nativeResetMetrics();
}
