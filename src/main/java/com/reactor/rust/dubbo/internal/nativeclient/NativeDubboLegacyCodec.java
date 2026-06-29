package com.reactor.rust.dubbo.internal.nativeclient;

/**
 * Isolates Hessian Lite usage from the no-arg byte[] native fast path.
 *
 * <p>Keep this class out of hot native invokers unless argument encoding or non-byte[] response
 * decoding is actually required.</p>
 */
public final class NativeDubboLegacyCodec {

    private NativeDubboLegacyCodec() {
    }

    public static Object newPlan(
            String serviceName,
            String group,
            String version,
            String methodName,
            Class<?> returnType,
            Class<?>[] parameterTypes,
            String parameterTypesDesc,
            ClassLoader codecClassLoader
    ) {
        return new NativeDubboCodec.MethodPlan(
                serviceName,
                group,
                version,
                methodName,
                returnType,
                parameterTypes,
                parameterTypesDesc,
                codecClassLoader
        );
    }

    public static byte[] encodeRequest(Object plan, Object[] args, int timeoutMs) {
        return NativeDubboCodec.encodeRequest((NativeDubboCodec.MethodPlan) plan, args, timeoutMs);
    }

    public static <R> R decodeResponse(byte[] body, Object plan) {
        return NativeDubboCodec.decodeResponse(body, (NativeDubboCodec.MethodPlan) plan);
    }
}
