package com.reactor.rust.dubbo.internal.nativeclient;

import com.reactor.rust.dubbo.DubboConsumerException;

import com.alibaba.com.caucho.hessian.io.Hessian2Input;
import com.alibaba.com.caucho.hessian.io.Hessian2Output;
import com.alibaba.com.caucho.hessian.io.SerializerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReferenceArray;

public final class NativeDubboCodec {

    private static final String DUBBO_PROTOCOL_VERSION = "2.0.2";
    private static final int RESPONSE_WITH_EXCEPTION = 0;
    private static final int RESPONSE_VALUE = 1;
    private static final int RESPONSE_NULL_VALUE = 2;
    private static final int RESPONSE_WITH_EXCEPTION_WITH_ATTACHMENTS = 3;
    private static final int RESPONSE_VALUE_WITH_ATTACHMENTS = 4;
    private static final int RESPONSE_NULL_VALUE_WITH_ATTACHMENTS = 5;
    private static final int REQUEST_BUFFER_INITIAL_BYTES = Integer.getInteger(
            "reactor.dubbo.hessian.request-buffer-initial-bytes",
            256
    );
    private static final int REQUEST_BUFFER_RETAIN_MAX_BYTES = Integer.getInteger(
            "reactor.dubbo.hessian.request-buffer-retain-max-bytes",
            4 * 1024
    );
    // A process bound, not a ThreadLocal: arbitrary caller/virtual threads cannot grow retention.
    private static final AtomicReferenceArray<RequestCodec> REQUEST_CODECS = new AtomicReferenceArray<>(2);
    private static final ConcurrentHashMap<String, Map<String, Object>> ATTACHMENTS_CACHE =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<ClassLoader, SerializerFactory> SERIALIZER_FACTORIES =
            new ConcurrentHashMap<>();

    private NativeDubboCodec() {}

    public static byte[] encodeRequest(MethodPlan plan, Object[] args, int timeoutMs) {
        RequestCodec codec = borrowRequestCodec();
        boolean reusable = false;
        try {
            ReusableByteArrayOutputStream bytes = codec.bytes;
            bytes.reset();
            Hessian2Output out = codec.output;
            out.init(bytes);
            out.setSerializerFactory(serializerFactory(plan));
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
            byte[] encoded = bytes.toByteArray();
            reusable = bytes.capacity() <= REQUEST_BUFFER_RETAIN_MAX_BYTES;
            return encoded;
        } catch (IOException e) {
            throw new DubboConsumerException("Failed to encode native Dubbo request for "
                    + plan.serviceName + "." + plan.methodName, e);
        } finally {
            // free() clears object/class references and detaches the output stream, even on failure.
            codec.output.free();
            codec.output.setSerializerFactory(null);
            codec.bytes.reset();
            if (reusable) {
                for (int slot = 0; slot < REQUEST_CODECS.length(); slot++) {
                    if (REQUEST_CODECS.compareAndSet(slot, null, codec)) break;
                }
            }
        }
    }

    private static RequestCodec borrowRequestCodec() {
        for (int slot = 0; slot < REQUEST_CODECS.length(); slot++) {
            RequestCodec codec = REQUEST_CODECS.getAndSet(slot, null);
            if (codec != null) return codec;
        }
        // Concurrent/reentrant calls own independent codecs; excess instances are not retained.
        return new RequestCodec();
    }

    @SuppressWarnings("unchecked")
    public static <R> R decodeResponse(byte[] body, MethodPlan plan) {
        try {
            Hessian2Input in = new Hessian2Input(new ByteArrayInputStream(body));
            in.setSerializerFactory(serializerFactory(plan));
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
        String key = plan.serviceName + '\u0000'
                + plan.group + '\u0000'
                + plan.version + '\u0000'
                + timeoutMs;
        return ATTACHMENTS_CACHE.computeIfAbsent(key, ignored -> createAttachments(plan, timeoutMs));
    }

    private static Map<String, Object> createAttachments(MethodPlan plan, int timeoutMs) {
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
        return Map.copyOf(map);
    }

    private static SerializerFactory serializerFactory(MethodPlan plan) {
        ClassLoader loader = plan.codecClassLoader;
        if (loader == null) {
            loader = NativeDubboCodec.class.getClassLoader();
        }
        if (loader == null) {
            loader = ClassLoader.getSystemClassLoader();
        }
        return SERIALIZER_FACTORIES.computeIfAbsent(loader, SerializerFactory::new);
    }

    public record MethodPlan(
            String serviceName,
            String group,
            String version,
            String methodName,
            Class<?> returnType,
            Class<?>[] parameterTypes,
            String parameterTypesDesc,
            ClassLoader codecClassLoader) {}

    private static final class ReusableByteArrayOutputStream extends ByteArrayOutputStream {
        private ReusableByteArrayOutputStream(int size) {
            super(Math.max(64, size));
        }

        private int capacity() {
            return buf.length;
        }
    }

    private static final class RequestCodec {
        private final ReusableByteArrayOutputStream bytes =
                new ReusableByteArrayOutputStream(REQUEST_BUFFER_INITIAL_BYTES);
        private final Hessian2Output output = new Hessian2Output();
    }
}
