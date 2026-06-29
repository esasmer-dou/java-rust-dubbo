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

class NativeDubboCodecTest {

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
