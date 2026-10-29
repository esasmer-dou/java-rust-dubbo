package com.reactor.rust.dubbo.internal.nativeclient;

import com.reactor.rust.dubbo.DubboConsumerException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LegacyCodecSupportTest {
    @Test
    void staticCallsKeepTheLegacyWireBytesAndPrimitiveTimeout() {
        Object plan = LegacyCodecSupport.newPlan("example.Customer", null, "0.0.0", "lookup",
                String.class, new Class<?>[]{long.class}, "J", getClass().getClassLoader());
        for (int timeout : new int[]{150, 1000, 5000}) {
            assertArrayEquals(NativeDubboLegacyCodec.encodeRequest(plan, new Object[]{42L}, timeout),
                    LegacyCodecSupport.encodeRequest(plan, new Object[]{42L}, timeout));
        }
        assertEquals("ok", LegacyCodecSupport.decodeResponse(new byte[]{(byte) 0x91, 2, 'o', 'k'}, plan));
        assertNull(LegacyCodecSupport.decodeResponse(new byte[]{(byte) 0x92}, plan));
    }

    @Test
    void decodeFailureRemainsAnActionableConsumerException() {
        Object plan = LegacyCodecSupport.newPlan("example.Customer", null, "0.0.0", "lookup",
                String.class, new Class<?>[0], "", getClass().getClassLoader());
        assertThrows(DubboConsumerException.class, () -> LegacyCodecSupport.decodeResponse(new byte[0], plan));
    }

    @Test
    void nativeOnlyClasspathLoadsTheInvokerWithoutResolvingTheOptionalCodec() throws Exception {
        var location = LegacyCodecSupport.class.getProtectionDomain().getCodeSource().getLocation();
        var codecLoads = new java.util.concurrent.atomic.AtomicInteger();
        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{location}, null) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.contains("NativeDubboLegacyCodec") || name.contains("NativeDubboCodec")
                        || name.startsWith("com.alibaba.")) {
                    codecLoads.incrementAndGet(); throw new ClassNotFoundException(name);
                }
                return super.loadClass(name, resolve);
            }
        }) {
            Class.forName("com.reactor.rust.dubbo.NativeDubboMethodInvoker", true, loader).getDeclaredMethods();
            Class<?> support = Class.forName(LegacyCodecSupport.class.getName(), true, loader);
            support.getDeclaredMethods();
            assertEquals(0, codecLoads.get(), "Loading native invokers must not load optional Hessian code");
            var failure = assertThrows(java.lang.reflect.InvocationTargetException.class,
                    () -> support.getMethod("decodeResponse", byte[].class, Object.class)
                            .invoke(null, new byte[0], new Object()));
            assertEquals(DubboConsumerException.class.getName(), failure.getCause().getClass().getName());
            assertTrue(failure.getCause().getMessage().contains("legacy Hessian artifact"));
        }
    }
}
