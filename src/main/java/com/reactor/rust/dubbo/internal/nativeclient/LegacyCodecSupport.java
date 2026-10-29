package com.reactor.rust.dubbo.internal.nativeclient;

import com.reactor.rust.dubbo.DubboConsumerException;

/** Static calls link the optional codec only when a typed method actually needs it. */
public final class LegacyCodecSupport {
    private LegacyCodecSupport() {}

    public static Object newPlan(String serviceName, String group, String version, String methodName,
            Class<?> returnType, Class<?>[] parameterTypes, String parameterTypesDesc, ClassLoader codecClassLoader) {
        try {
            return NativeDubboLegacyCodec.newPlan(serviceName, group, version, methodName,
                    returnType, parameterTypes, parameterTypesDesc, codecClassLoader);
        } catch (NoClassDefFoundError missing) {
            throw unavailable(missing);
        } catch (RuntimeException failure) {
            throw propagate("Failed to create native Dubbo legacy codec plan", failure);
        }
    }

    public static byte[] encodeRequest(Object plan, Object[] args, int timeoutMs) {
        try {
            return NativeDubboLegacyCodec.encodeRequest(plan, args, timeoutMs);
        } catch (NoClassDefFoundError missing) {
            throw unavailable(missing);
        } catch (RuntimeException failure) {
            throw propagate("Failed to encode native Dubbo legacy request", failure);
        }
    }

    public static <R> R decodeResponse(byte[] body, Object plan) {
        try {
            return NativeDubboLegacyCodec.decodeResponse(body, plan);
        } catch (NoClassDefFoundError missing) {
            throw unavailable(missing);
        } catch (RuntimeException failure) {
            throw propagate("Failed to decode native Dubbo legacy response", failure);
        }
    }

    private static DubboConsumerException unavailable(NoClassDefFoundError missing) {
        return new DubboConsumerException("This Dubbo method requires Hessian legacy encoding/decoding. "
                + "Use the full java-rust-dubbo artifact or add the legacy Hessian artifact/classifier. "
                + "The native-static artifact supports the lowest-RSS byte[] no-arg fast path.", missing);
    }

    private static DubboConsumerException propagate(String message, RuntimeException failure) {
        return failure instanceof DubboConsumerException consumer ? consumer : new DubboConsumerException(message, failure);
    }
}
