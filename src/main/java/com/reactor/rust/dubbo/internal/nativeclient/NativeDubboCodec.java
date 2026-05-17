package com.reactor.rust.dubbo.internal.nativeclient;

import com.reactor.rust.dubbo.DubboConsumerException;

import com.alibaba.com.caucho.hessian.io.Hessian2Input;
import com.alibaba.com.caucho.hessian.io.Hessian2Output;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public final class NativeDubboCodec {

    private static final String DUBBO_PROTOCOL_VERSION = "2.0.2";
    private static final int RESPONSE_WITH_EXCEPTION = 0;
    private static final int RESPONSE_VALUE = 1;
    private static final int RESPONSE_NULL_VALUE = 2;
    private static final int RESPONSE_WITH_EXCEPTION_WITH_ATTACHMENTS = 3;
    private static final int RESPONSE_VALUE_WITH_ATTACHMENTS = 4;
    private static final int RESPONSE_NULL_VALUE_WITH_ATTACHMENTS = 5;

    private NativeDubboCodec() {}

    public static byte[] encodeRequest(MethodPlan plan, Object[] args, int timeoutMs) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(256);
            Hessian2Output out = new Hessian2Output(bytes);
            out.writeString(DUBBO_PROTOCOL_VERSION);
            out.writeString(plan.serviceName);
            out.writeString(plan.version);
            out.writeString(plan.methodName);
            out.writeString(plan.parameterTypesDesc);
            for (Object arg : args) {
                out.writeObject(arg);
            }
            out.writeObject(attachments(plan, timeoutMs));
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new DubboConsumerException("Failed to encode native Dubbo request for "
                    + plan.serviceName + "." + plan.methodName, e);
        }
    }

    @SuppressWarnings("unchecked")
    public static <R> R decodeResponse(byte[] body, MethodPlan plan) {
        try {
            Hessian2Input in = new Hessian2Input(new ByteArrayInputStream(body));
            int flag = in.readInt();
            Object value = null;
            switch (flag) {
                case RESPONSE_NULL_VALUE -> {
                    return null;
                }
                case RESPONSE_VALUE -> value = in.readObject(plan.returnType);
                case RESPONSE_NULL_VALUE_WITH_ATTACHMENTS -> {
                    in.readObject(Map.class);
                    return null;
                }
                case RESPONSE_VALUE_WITH_ATTACHMENTS -> {
                    value = in.readObject(plan.returnType);
                    in.readObject(Map.class);
                }
                case RESPONSE_WITH_EXCEPTION, RESPONSE_WITH_EXCEPTION_WITH_ATTACHMENTS -> {
                    Object error = in.readObject();
                    if (flag == RESPONSE_WITH_EXCEPTION_WITH_ATTACHMENTS) {
                        in.readObject(Map.class);
                    }
                    if (error instanceof Throwable throwable) {
                        throw new DubboConsumerException("Native Dubbo provider exception for "
                                + plan.serviceName + "." + plan.methodName, throwable);
                    }
                    throw new DubboConsumerException("Native Dubbo provider returned exception payload: " + error);
                }
                default -> throw new DubboConsumerException("Unknown native Dubbo response flag " + flag);
            }
            if (plan.returnType == Void.TYPE || plan.returnType == Void.class) {
                return null;
            }
            return (R) value;
        } catch (IOException e) {
            throw new DubboConsumerException("Failed to decode native Dubbo response for "
                    + plan.serviceName + "." + plan.methodName, e);
        }
    }

    private static Map<String, Object> attachments(MethodPlan plan, int timeoutMs) {
        Map<String, Object> map = new HashMap<>(8);
        map.put("interface", plan.serviceName);
        map.put("path", plan.serviceName);
        map.put("side", "consumer");
        map.put("timeout", Integer.toString(timeoutMs));
        map.put("dubbo", DUBBO_PROTOCOL_VERSION);
        if (plan.group != null) {
            map.put("group", plan.group);
        }
        if (plan.version != null) {
            map.put("version", plan.version);
        }
        return map;
    }

    public record MethodPlan(
            String serviceName,
            String group,
            String version,
            String methodName,
            Class<?> returnType,
            Class<?>[] parameterTypes,
            String parameterTypesDesc) {}
}
