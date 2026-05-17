package com.reactor.rust.dubbo;

import com.reactor.rust.dubbo.internal.nativeclient.NativeDubboCodec;
import com.reactor.rust.dubbo.internal.nativeclient.NativeDubboDescriptor;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

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
    private volatile NativeDubboCodec.MethodPlan legacyPlan;
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
        NativeDubboCodec.MethodPlan plan = legacyPlan();
        byte[] requestBody = requestBody(plan, args);
        byte[] responseBody = NativeDubboBridge.invoke(clientId, requestBody, timeoutMs);
        return NativeDubboCodec.decodeResponse(responseBody, plan);
    }

    private CompletableFuture<R> invokeAsyncWithArgs(Object[] args) {
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
        NativeDubboCodec.MethodPlan plan = legacyPlan();
        byte[] requestBody = requestBody(plan, args);
        return NativeDubboBridge.invokeAsync(clientId, requestBody, timeoutMs)
                .thenApply(responseBody -> NativeDubboCodec.decodeResponse(responseBody, plan));
    }

    private void validateArgs(Object[] args) {
        if (args.length != parameterTypes.length) {
            throw new IllegalArgumentException("Expected " + parameterTypes.length + " arguments for "
                    + serviceName + "." + methodName + " but got " + args.length);
        }
    }

    private byte[] requestBody(NativeDubboCodec.MethodPlan plan, Object[] args) {
        if (args.length != 0) {
            return NativeDubboCodec.encodeRequest(plan, args, timeoutMs);
        }
        byte[] current = precomputedNoArgRequestBody;
        if (current == null) {
            current = NativeDubboCodec.encodeRequest(plan, EMPTY_ARGS, timeoutMs);
            precomputedNoArgRequestBody = current;
        }
        return current;
    }

    private NativeDubboCodec.MethodPlan legacyPlan() {
        NativeDubboCodec.MethodPlan current = legacyPlan;
        if (current == null) {
            current = new NativeDubboCodec.MethodPlan(
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
