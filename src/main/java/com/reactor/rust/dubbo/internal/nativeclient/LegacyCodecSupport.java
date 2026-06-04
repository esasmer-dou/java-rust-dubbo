package com.reactor.rust.dubbo.internal.nativeclient;

import com.reactor.rust.dubbo.DubboConsumerException;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public final class LegacyCodecSupport {

    private static final String CODEC_CLASS =
            "com.reactor.rust.dubbo.internal.nativeclient.NativeDubboLegacyCodec";
    private static final String NEW_PLAN = "newPlan";
    private static final String ENCODE_REQUEST = "encodeRequest";
    private static final String DECODE_RESPONSE = "decodeResponse";
    private static final CodecMethods METHODS = loadMethods();

    private LegacyCodecSupport() {
    }

    public static Object newPlan(
            String serviceName,
            String group,
            String version,
            String methodName,
            Class<?> returnType,
            Class<?>[] parameterTypes,
            String parameterTypesDesc
    ) {
        CodecMethods methods = methods();
        try {
            return methods.newPlan.invoke(
                    null,
                    serviceName,
                    group,
                    version,
                    methodName,
                    returnType,
                    parameterTypes,
                    parameterTypesDesc);
        } catch (IllegalAccessException e) {
            throw new DubboConsumerException("Invalid native Dubbo legacy codec access", e);
        } catch (InvocationTargetException e) {
            throw propagate("Failed to create native Dubbo legacy codec plan", e);
        }
    }

    public static byte[] encodeRequest(Object plan, Object[] args, int timeoutMs) {
        CodecMethods methods = methods();
        try {
            return (byte[]) methods.encodeRequest.invoke(null, plan, args, timeoutMs);
        } catch (IllegalAccessException e) {
            throw new DubboConsumerException("Invalid native Dubbo legacy codec access", e);
        } catch (InvocationTargetException e) {
            throw propagate("Failed to encode native Dubbo legacy request", e);
        }
    }

    @SuppressWarnings("unchecked")
    public static <R> R decodeResponse(byte[] body, Object plan) {
        CodecMethods methods = methods();
        try {
            return (R) methods.decodeResponse.invoke(null, body, plan);
        } catch (IllegalAccessException e) {
            throw new DubboConsumerException("Invalid native Dubbo legacy codec access", e);
        } catch (InvocationTargetException e) {
            throw propagate("Failed to decode native Dubbo legacy response", e);
        }
    }

    private static CodecMethods methods() {
        if (METHODS == null) {
            throw new DubboConsumerException("This Dubbo method requires Hessian legacy encoding/decoding. "
                    + "Use the full java-rust-dubbo artifact or add the legacy Hessian artifact/classifier. "
                    + "The native-static artifact supports the lowest-RSS byte[] no-arg fast path.");
        }
        return METHODS;
    }

    private static CodecMethods loadMethods() {
        try {
            Class<?> codec = Class.forName(CODEC_CLASS, false, LegacyCodecSupport.class.getClassLoader());
            return new CodecMethods(
                    codec.getMethod(NEW_PLAN,
                            String.class,
                            String.class,
                            String.class,
                            String.class,
                            Class.class,
                            Class[].class,
                            String.class),
                    codec.getMethod(ENCODE_REQUEST, Object.class, Object[].class, int.class),
                    codec.getMethod(DECODE_RESPONSE, byte[].class, Object.class));
        } catch (ClassNotFoundException e) {
            return null;
        } catch (NoSuchMethodException e) {
            throw new DubboConsumerException("Invalid native Dubbo legacy codec wiring", e);
        }
    }

    private static DubboConsumerException propagate(String message, InvocationTargetException e) {
        Throwable cause = e.getCause();
        if (cause instanceof DubboConsumerException dubboException) {
            return dubboException;
        }
        if (cause instanceof RuntimeException runtimeException) {
            return new DubboConsumerException(message, runtimeException);
        }
        return new DubboConsumerException(message, cause);
    }

    private record CodecMethods(Method newPlan, Method encodeRequest, Method decodeResponse) {
    }
}
