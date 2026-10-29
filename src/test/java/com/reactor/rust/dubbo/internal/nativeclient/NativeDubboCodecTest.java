package com.reactor.rust.dubbo.internal.nativeclient;

import com.alibaba.com.caucho.hessian.io.Hessian2Input;
import com.alibaba.com.caucho.hessian.io.Hessian2Output;
import com.alibaba.com.caucho.hessian.io.SerializerFactory;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NativeDubboCodecTest {

    @Test
    void responsesRemainIndependentAcrossNullLargeFailedAndTrailingMessages() throws Exception {
        var plan = responsePlan(List.class);
        byte[] firstWire = response(4, List.of(new CustomerPayload("C-1", "İpek Şahin 🌍")));
        List<CustomerPayload> first = NativeDubboCodec.decodeResponse(firstWire, plan);
        for (int i = 0; i < 20; i++) {
            List<CustomerPayload> next = NativeDubboCodec.decodeResponse(
                    response(1, List.of(new CustomerPayload("C-2", "başka"))), plan);
            assertEquals("C-2", next.get(0).customerNo());
            assertEquals("İpek Şahin 🌍", first.get(0).fullName());
            assertNull(NativeDubboCodec.decodeResponse(response(2, null), plan));
            assertNull(NativeDubboCodec.decodeResponse(response(5, null), plan));
        }
        byte[] trailing = java.util.Arrays.copyOf(firstWire, firstWire.length + 1);
        NativeDubboCodec.decodeResponse(trailing, plan);
        assertThrows(com.reactor.rust.dubbo.DubboConsumerException.class,
                () -> NativeDubboCodec.decodeResponse(new byte[]{(byte) 0xff}, plan));
        assertThrows(com.reactor.rust.dubbo.DubboConsumerException.class,
                () -> NativeDubboCodec.decodeResponse(response(0, "provider failed"), plan));
        assertThrows(com.reactor.rust.dubbo.DubboConsumerException.class,
                () -> NativeDubboCodec.decodeResponse(new byte[0], plan));
        String large = "Çağrı".repeat(16_384);
        assertEquals(large, NativeDubboCodec.decodeResponse(response(1, large), responsePlan(String.class)));
        List<CustomerPayload> last = NativeDubboCodec.decodeResponse(firstWire, plan);
        assertEquals(first, last);
    }

    @Test
    void responseStateDoesNotCrossConcurrentCallers() throws Exception {
        var plan = responsePlan(List.class);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            var tasks = new java.util.ArrayList<java.util.concurrent.Callable<Void>>();
            for (int thread = 0; thread < 8; thread++) {
                String name = "customer-" + thread;
                byte[] wire = response(4, List.of(new CustomerPayload(name, "Çağrı 🌍")));
                tasks.add(() -> {
                    for (int i = 0; i < 100; i++) {
                        List<CustomerPayload> result = NativeDubboCodec.decodeResponse(wire, plan);
                        assertEquals(name, result.get(0).customerNo());
                        assertEquals("Çağrı 🌍", result.get(0).fullName());
                    }
                    return null;
                });
            }
            for (var result : executor.invokeAll(tasks)) result.get();
        }
    }

    private static NativeDubboCodec.MethodPlan responsePlan(Class<?> type) {
        return new NativeDubboCodec.MethodPlan("example.Service", null, "1", "read", type,
                new Class<?>[0], "", NativeDubboCodecTest.class.getClassLoader());
    }

    private static byte[] response(int flag, Object value) throws Exception {
        var bytes = new ByteArrayOutputStream();
        var out = new Hessian2Output(bytes);
        out.setSerializerFactory(new SerializerFactory(NativeDubboCodecTest.class.getClassLoader()));
        out.writeInt(flag);
        if (flag != 2 && flag != 5) out.writeObject(value);
        if (flag == 4 || flag == 5) out.writeObject(Map.of("dubbo", "2.0.2"));
        out.flush();
        return bytes.toByteArray();
    }

    @Test
    void pooledRequestsRemainIndependentAcrossLargeAndFailedMessages() throws Exception {
        var plan = new NativeDubboCodec.MethodPlan("example.Service", "group", "1", "echo", byte[].class,
                new Class<?>[]{String.class}, "Ljava/lang/String;", getClass().getClassLoader());
        byte[] first = NativeDubboCodec.encodeRequest(plan, new Object[]{"ilk"}, 800);
        for (String value : List.of("ikinci", "x".repeat(16_384), "Türkçe 🌍", "son")) {
            byte[] wire = NativeDubboCodec.encodeRequest(plan, new Object[]{value}, 800);
            var in = new Hessian2Input(new ByteArrayInputStream(wire));
            for (int i = 0; i < 5; i++) in.readString();
            assertEquals(value, in.readString());
        }
        assertThrows(NullPointerException.class, () -> NativeDubboCodec.encodeRequest(plan, null, 800));
        assertArrayEquals(first, NativeDubboCodec.encodeRequest(plan, new Object[]{"ilk"}, 800));
    }

    @Test
    void pooledRequestStateDoesNotCrossConcurrentCallers() throws Exception {
        var plan = new NativeDubboCodec.MethodPlan("example.Service", null, "1", "echo", String.class,
                new Class<?>[]{String.class}, "Ljava/lang/String;", getClass().getClassLoader());
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            var tasks = new java.util.ArrayList<java.util.concurrent.Callable<Void>>();
            for (int thread = 0; thread < 8; thread++) {
                String prefix = "request-" + thread + "-";
                tasks.add(() -> {
                    for (int i = 0; i < 100; i++) {
                        String expected = prefix + i;
                        var in = new Hessian2Input(new ByteArrayInputStream(
                                NativeDubboCodec.encodeRequest(plan, new Object[]{expected}, 800)));
                        for (int n = 0; n < 5; n++) in.readString();
                        assertEquals(expected, in.readString());
                    }
                    return null;
                });
            }
            for (var result : executor.invokeAll(tasks)) result.get();
        }
    }

    @Test
    void encodesNoArgRequestInDubboOrder() throws Exception {
        Method method = CatalogService.class.getMethod("json");
        NativeDubboCodec.MethodPlan plan = new NativeDubboCodec.MethodPlan(
                CatalogService.class.getName(),
                null,
                null,
                method.getName(),
                byte[].class,
                method.getParameterTypes(),
                NativeDubboDescriptor.parameterTypesDesc(method.getParameterTypes()),
                CatalogService.class.getClassLoader());

        byte[] encoded = NativeDubboCodec.encodeRequest(plan, new Object[0], 800);

        Hessian2Input in = new Hessian2Input(new ByteArrayInputStream(encoded));
        assertEquals("2.0.2", in.readString());
        assertEquals(CatalogService.class.getName(), in.readString());
        assertNull(in.readString());
        assertEquals("json", in.readString());
        assertEquals("", in.readString());
        @SuppressWarnings("unchecked")
        Map<String, Object> attachments = (Map<String, Object>) in.readObject(Map.class);
        assertEquals(CatalogService.class.getName(), attachments.get("interface"));
        assertEquals("800", attachments.get("timeout"));
    }

    @Test
    void decodesValueWithAttachmentsResponse() throws Exception {
        Method method = CatalogService.class.getMethod("json");
        NativeDubboCodec.MethodPlan plan = new NativeDubboCodec.MethodPlan(
                CatalogService.class.getName(),
                null,
                null,
                method.getName(),
                byte[].class,
                method.getParameterTypes(),
                "",
                CatalogService.class.getClassLoader());
        byte[] payload = "{\"ok\":true}".getBytes();

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Hessian2Output out = new Hessian2Output(bytes);
        out.writeInt(4);
        out.writeObject(payload);
        out.writeObject(Map.of("dubbo", "2.0.2"));
        out.flush();

        assertArrayEquals(payload, NativeDubboCodec.decodeResponse(bytes.toByteArray(), plan));
    }

    @Test
    void decodesTypedListWhenThreadContextClassLoaderIsMissing() throws Exception {
        Method method = CatalogService.class.getMethod("typedCustomers");
        NativeDubboCodec.MethodPlan plan = new NativeDubboCodec.MethodPlan(
                CatalogService.class.getName(),
                null,
                null,
                method.getName(),
                List.class,
                method.getParameterTypes(),
                "",
                CatalogService.class.getClassLoader());

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Hessian2Output out = new Hessian2Output(bytes);
        out.setSerializerFactory(new SerializerFactory(CatalogService.class.getClassLoader()));
        out.writeInt(4);
        out.writeObject(List.of(new CustomerPayload("C-1", "Ada Lovelace")));
        out.writeObject(Map.of("dubbo", "2.0.2"));
        out.flush();

        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(null);
        try {
            @SuppressWarnings("unchecked")
            List<Object> decoded = NativeDubboCodec.decodeResponse(bytes.toByteArray(), plan);
            Object first = decoded.get(0);
            CustomerPayload customer = assertInstanceOf(CustomerPayload.class, first);
            assertEquals("C-1", customer.customerNo());
            assertEquals("Ada Lovelace", customer.fullName());
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    interface CatalogService {
        byte[] json();

        List<CustomerPayload> typedCustomers();
    }

    record CustomerPayload(String customerNo, String fullName) implements Serializable {
    }
}
