package com.reactor.rust.dubbo;

import java.util.concurrent.CompletableFuture;

public final class NativeDubboBridge {

    private static final int EXPECTED_DUBBO_NATIVE_ABI_VERSION = 5;
    private static final PendingNativeDubboInvocations PENDING = new PendingNativeDubboInvocations();

    private NativeDubboBridge() {}

    static {
        loadNativeLibrary();
        verifyNativeDubboAbi();
    }

    public static int createClient(int timeoutMs, int maxInflight, int maxResponseBytes, int maxConnectionsPerEndpoint) {
        return createClient(timeoutMs, maxInflight, maxResponseBytes, maxConnectionsPerEndpoint, maxConnectionsPerEndpoint);
    }

    public static int createClient(
            int timeoutMs,
            int maxInflight,
            int maxResponseBytes,
            int maxConnectionsPerEndpoint,
            int maxIdleConnectionsPerEndpoint) {
        int id = nativeCreateClientWithIdleLimit(
                timeoutMs,
                maxInflight,
                maxResponseBytes,
                maxConnectionsPerEndpoint,
                maxIdleConnectionsPerEndpoint);
        if (id <= 0) {
            throw new DubboConsumerException("Failed to create native Dubbo client");
        }
        return id;
    }

    public static int updateProviders(int clientId, String providers) {
        return nativeUpdateProviders(clientId, providers == null ? "" : providers);
    }

    public static void configureAsync(int workers, int queueCapacity) {
        nativeConfigureAsync(workers, queueCapacity);
    }

    public static void configureAsync(int workers, int queueCapacity, String transport) {
        nativeConfigureAsync(workers, queueCapacity);
        nativeConfigureAsyncTransport(transport == null ? "blocking" : transport);
    }

    public static byte[] invoke(int clientId, byte[] requestBody, int timeoutMs) {
        return nativeInvoke(clientId, requestBody, timeoutMs);
    }

    public static CompletableFuture<byte[]> invokeAsync(int clientId, byte[] requestBody, int timeoutMs) {
        PendingNativeDubboInvocations.PendingCall pending = PENDING.begin(clientId);
        boolean accepted = nativeInvokeAsync(clientId, requestBody, timeoutMs, pending.callbackId());
        if (!accepted) {
            PENDING.rejected(pending, "Native Dubbo async queue rejected the call");
        } else {
            PENDING.accepted(pending, timeoutMs);
        }
        return pending.future();
    }

    @SuppressWarnings("unchecked")
    public static <R> R invokeByteArrayNoArgs(
            int clientId,
            String serviceName,
            String group,
            String version,
            String methodName,
            int timeoutMs) {
        return (R) nativeInvokeByteArrayNoArgs(clientId, serviceName, group, version, methodName, timeoutMs);
    }

    public static CompletableFuture<byte[]> invokeByteArrayNoArgsAsync(
            int clientId,
            String serviceName,
            String group,
            String version,
            String methodName,
            int timeoutMs) {
        PendingNativeDubboInvocations.PendingCall pending = PENDING.begin(clientId);
        boolean accepted = nativeInvokeByteArrayNoArgsAsync(
                clientId,
                serviceName,
                group,
                version,
                methodName,
                timeoutMs,
                pending.callbackId());
        if (!accepted) {
            PENDING.rejected(pending, "Native Dubbo async queue rejected the call");
        } else {
            PENDING.accepted(pending, timeoutMs);
        }
        return pending.future();
    }

    public static CompletableFuture<NativeResponseHandle> invokeByteArrayNoArgsNativeJsonAsync(
            int clientId,
            String serviceName,
            String group,
            String version,
            String methodName,
            int timeoutMs) {
        PendingNativeDubboInvocations.PendingNativeResponseCall pending = PENDING.beginNativeResponse(clientId);
        boolean accepted = nativeInvokeByteArrayNoArgsNativeJsonAsync(
                clientId,
                serviceName,
                group,
                version,
                methodName,
                timeoutMs,
                pending.callbackId());
        if (!accepted) {
            PENDING.rejected(pending, "Native Dubbo async queue rejected the call");
        } else {
            PENDING.accepted(pending, timeoutMs);
        }
        return pending.future();
    }

    public static CompletableFuture<NativeResponseHandle> invokeByteArrayArgNativeJsonAsync(
            int clientId,
            String serviceName,
            String group,
            String version,
            String methodName,
            byte[] arg0,
            int timeoutMs) {
        PendingNativeDubboInvocations.PendingNativeResponseCall pending = PENDING.beginNativeResponse(clientId);
        boolean accepted = nativeInvokeByteArrayArgNativeJsonAsync(
                clientId,
                serviceName,
                group,
                version,
                methodName,
                arg0 == null ? new byte[0] : arg0,
                timeoutMs,
                pending.callbackId());
        if (!accepted) {
            PENDING.rejected(pending, "Native Dubbo async queue rejected the call");
        } else {
            PENDING.accepted(pending, timeoutMs);
        }
        return pending.future();
    }

    public static CompletableFuture<NativeResponseHandle> invokeLongByteArrayArgsNativeJsonAsync(
            int clientId,
            String serviceName,
            String group,
            String version,
            String methodName,
            long arg0,
            byte[] arg1,
            int timeoutMs) {
        PendingNativeDubboInvocations.PendingNativeResponseCall pending = PENDING.beginNativeResponse(clientId);
        boolean accepted = nativeInvokeLongByteArrayArgsNativeJsonAsync(
                clientId,
                serviceName,
                group,
                version,
                methodName,
                arg0,
                arg1 == null ? new byte[0] : arg1,
                timeoutMs,
                pending.callbackId());
        if (!accepted) {
            PENDING.rejected(pending, "Native Dubbo async queue rejected the call");
        } else {
            PENDING.accepted(pending, timeoutMs);
        }
        return pending.future();
    }

    public static void closeClient(int clientId) {
        try {
            nativeCloseClient(clientId);
        } finally {
            PENDING.closeClient(clientId, new DubboConsumerException("Native Dubbo client was closed"));
        }
    }

    public static String metricsJson() {
        return nativeMetricsJson();
    }

    public static void resetMetrics() {
        nativeResetMetrics();
    }

    private static void loadNativeLibrary() {
        ClassLoader ownLoader = NativeDubboBridge.class.getClassLoader();
        if (loadFrameworkNativeBridge(ownLoader)) {
            return;
        }

        ClassLoader systemLoader = ClassLoader.getSystemClassLoader();
        if (systemLoader != ownLoader && loadFrameworkNativeBridge(systemLoader)) {
            return;
        }

        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        if (contextLoader != ownLoader
                && contextLoader != systemLoader
                && loadFrameworkNativeBridge(contextLoader)) {
            return;
        }
        System.loadLibrary("rust_hyper");
    }

    private static void verifyNativeDubboAbi() {
        int actual;
        try {
            actual = nativeDubboAbiVersion();
        } catch (UnsatisfiedLinkError e) {
            throw new DubboConsumerException(
                    "Native rust_hyper library does not expose the java-rust-dubbo ABI. "
                            + "Rebuild rust-spring and update the native DLL/SO resources.",
                    e);
        }
        if (actual != EXPECTED_DUBBO_NATIVE_ABI_VERSION) {
            throw new DubboConsumerException(
                    "Native java-rust-dubbo ABI mismatch: expected "
                            + EXPECTED_DUBBO_NATIVE_ABI_VERSION
                            + " but loaded "
                            + actual
                            + ". Rebuild rust-spring and update native resources.");
        }
    }

    private static boolean loadFrameworkNativeBridge(ClassLoader loader) {
        if (loader == null) {
            return false;
        }
        try {
            Class.forName("com.reactor.rust.bridge.NativeBridge", true, loader);
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }

    private static void completeInvoke(long callbackId, byte[] responseBody, String errorMessage) {
        PENDING.complete(callbackId, responseBody, errorMessage);
    }

    private static void completeNativeResponseInvoke(
            long callbackId,
            int nativeResponseId,
            int statusCode,
            String contentType,
            String headers,
            String errorMessage) {
        PENDING.completeNativeResponse(callbackId, nativeResponseId, statusCode, contentType, headers, errorMessage);
    }

    static int pendingCountForTest() {
        return PENDING.size();
    }

    private static native int nativeDubboAbiVersion();

    private static native int nativeCreateClient(
            int timeoutMs,
            int maxInflight,
            int maxResponseBytes,
            int maxConnectionsPerEndpoint);

    private static native int nativeCreateClientWithIdleLimit(
            int timeoutMs,
            int maxInflight,
            int maxResponseBytes,
            int maxConnectionsPerEndpoint,
            int maxIdleConnectionsPerEndpoint);

    private static native int nativeUpdateProviders(int clientId, String providers);

    private static native void nativeConfigureAsync(int workers, int queueCapacity);

    private static native void nativeConfigureAsyncTransport(String transport);

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

    private static native boolean nativeInvokeByteArrayNoArgsNativeJsonAsync(
            int clientId,
            String serviceName,
            String group,
            String version,
            String methodName,
            int timeoutMs,
            long callbackId);

    private static native boolean nativeInvokeByteArrayArgNativeJsonAsync(
            int clientId,
            String serviceName,
            String group,
            String version,
            String methodName,
            byte[] arg0,
            int timeoutMs,
            long callbackId);

    private static native boolean nativeInvokeLongByteArrayArgsNativeJsonAsync(
            int clientId,
            String serviceName,
            String group,
            String version,
            String methodName,
            long arg0,
            byte[] arg1,
            int timeoutMs,
            long callbackId);

    private static native void nativeCloseClient(int clientId);

    private static native String nativeMetricsJson();

    private static native void nativeResetMetrics();
}
