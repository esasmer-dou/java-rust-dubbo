package com.reactor.rust.dubbo;

import com.reactor.rust.dubbo.internal.nativeclient.LegacyCodecSupport;
import com.reactor.rust.dubbo.internal.nativeclient.NativeDubboDescriptor;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public final class NativeDubboMethodInvoker<R> {

    private static final Object[] EMPTY_ARGS = new Object[0];

    private final int clientId;
    private final int timeoutMs;
    private final String serviceName;
    private final String group;
    private final String version;
    private final String methodName;
    private final Class<R> returnType;
    private final Class<?>[] parameterTypes;
    private final String parameterTypesDesc;
    private final boolean nativeByteArrayNoArgs;
    private final Supplier<NativeDubboMethodInvoker<R>> delegateSupplier;
    private volatile NativeDubboMethodInvoker<R> delegate;
    private volatile Object legacyPlan;
    private volatile byte[] precomputedNoArgRequestBody;

    public NativeDubboMethodInvoker(
            int clientId,
            DubboConsumerConfig config,
            DubboReferenceSpec<?> spec,
            Method method,
            Class<R> returnType) {
        this.clientId = clientId;
        this.timeoutMs = valueOrDefault(spec.timeoutMs(), config.timeoutMs());
        this.serviceName = spec.serviceInterface().getName();
        this.group = spec.group();
        this.version = spec.version();
        this.methodName = method.getName();
        this.returnType = Objects.requireNonNull(returnType, "returnType");
        this.parameterTypes = method.getParameterTypes();
        this.parameterTypesDesc = NativeDubboDescriptor.parameterTypesDesc(parameterTypes);
        this.nativeByteArrayNoArgs = this.returnType == byte[].class && this.parameterTypes.length == 0;
        this.delegateSupplier = null;
    }

    private NativeDubboMethodInvoker(Supplier<NativeDubboMethodInvoker<R>> delegateSupplier) {
        this.clientId = 0;
        this.timeoutMs = 0;
        this.serviceName = "";
        this.group = null;
        this.version = null;
        this.methodName = "";
        this.returnType = null;
        this.parameterTypes = new Class<?>[0];
        this.parameterTypesDesc = "";
        this.nativeByteArrayNoArgs = false;
        this.delegateSupplier = Objects.requireNonNull(delegateSupplier, "delegateSupplier");
    }

    static <R> NativeDubboMethodInvoker<R> lazy(Supplier<NativeDubboMethodInvoker<R>> delegateSupplier) {
        return new NativeDubboMethodInvoker<>(delegateSupplier);
    }

    public R invoke() {
        return invokeWithArgs(EMPTY_ARGS);
    }

    public R invoke(Object arg0) {
        return invokeWithArgs(new Object[] {arg0});
    }

    public R invoke(Object... args) {
        return invokeWithArgs(args == null ? EMPTY_ARGS : args);
    }

    public CompletableFuture<R> invokeAsync() {
        return invokeAsyncWithArgs(EMPTY_ARGS);
    }

    public CompletableFuture<R> invokeAsync(Object arg0) {
        return invokeAsyncWithArgs(new Object[] {arg0});
    }

    public CompletableFuture<R> invokeAsync(Object... args) {
        return invokeAsyncWithArgs(args == null ? EMPTY_ARGS : args);
    }

    private R invokeWithArgs(Object[] args) {
        if (delegateSupplier != null) {
            return delegate().invokeWithArgs(args);
        }
        validateArgs(args);
        if (nativeByteArrayNoArgs) {
            return NativeDubboBridge.invokeByteArrayNoArgs(
                    clientId,
                    serviceName,
                    group,
                    version,
                    methodName,
                    timeoutMs);
        }
        Object plan = legacyPlan();
        byte[] requestBody = requestBody(plan, args);
        byte[] responseBody = NativeDubboBridge.invoke(clientId, requestBody, timeoutMs);
        return LegacyCodecSupport.decodeResponse(responseBody, plan);
    }

    private CompletableFuture<R> invokeAsyncWithArgs(Object[] args) {
        if (delegateSupplier != null) {
            return delegate().invokeAsyncWithArgs(args);
        }
        validateArgs(args);
        if (nativeByteArrayNoArgs) {
            @SuppressWarnings("unchecked")
            CompletableFuture<R> future = (CompletableFuture<R>) NativeDubboBridge.invokeByteArrayNoArgsAsync(
                    clientId,
                    serviceName,
                    group,
                    version,
                    methodName,
                    timeoutMs);
            return future;
        }
        Object plan = legacyPlan();
        byte[] requestBody = requestBody(plan, args);
        return NativeDubboBridge.invokeAsync(clientId, requestBody, timeoutMs)
                .thenApply(responseBody -> LegacyCodecSupport.decodeResponse(responseBody, plan));
    }

    private NativeDubboMethodInvoker<R> delegate() {
        NativeDubboMethodInvoker<R> current = delegate;
        if (current == null) {
            synchronized (this) {
                current = delegate;
                if (current == null) {
                    current = delegateSupplier.get();
                    delegate = current;
                }
            }
        }
        return current;
    }

    private void validateArgs(Object[] args) {
        if (args.length != parameterTypes.length) {
            throw new IllegalArgumentException("Expected " + parameterTypes.length + " arguments for "
                    + serviceName + "." + methodName + " but got " + args.length);
        }
    }

    private byte[] requestBody(Object plan, Object[] args) {
        if (args.length != 0) {
            return LegacyCodecSupport.encodeRequest(plan, args, timeoutMs);
        }
        byte[] current = precomputedNoArgRequestBody;
        if (current == null) {
            current = LegacyCodecSupport.encodeRequest(plan, EMPTY_ARGS, timeoutMs);
            precomputedNoArgRequestBody = current;
        }
        return current;
    }

    private Object legacyPlan() {
        Object current = legacyPlan;
        if (current == null) {
            current = LegacyCodecSupport.newPlan(
                    serviceName,
                    group,
                    version,
                    methodName,
                    returnType,
                    parameterTypes,
                    parameterTypesDesc);
            legacyPlan = current;
        }
        return current;
    }

    private static int valueOrDefault(Integer value, int defaultValue) {
        return value == null ? defaultValue : value;
    }
}
