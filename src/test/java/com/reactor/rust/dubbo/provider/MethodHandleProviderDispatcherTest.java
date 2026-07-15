package com.reactor.rust.dubbo.provider;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MethodHandleProviderDispatcherTest {

    @Test
    void dispatchesOverloadsPrimitivesAndVoidWithoutPerCallReflection() throws Throwable {
        TestServiceImpl service = new TestServiceImpl();
        MethodHandleProviderDispatcher<TestService> dispatcher =
                MethodHandleProviderDispatcher.create(TestService.class, service);

        assertEquals(7, dispatcher.invoke(
                service,
                "add",
                new Class<?>[]{int.class, int.class},
                new Object[]{3, 4}));
        assertEquals("id=42", dispatcher.invoke(
                service,
                "find",
                new Class<?>[]{long.class},
                new Object[]{42L}));
        assertEquals("name=alpha", dispatcher.invoke(
                service,
                "find",
                new Class<?>[]{String.class},
                new Object[]{"alpha"}));
        assertNull(dispatcher.invoke(service, "reset", new Class<?>[0], new Object[0]));
        assertEquals(1, service.resetCount);
    }

    @Test
    void propagatesBusinessFailureWithoutInvocationTargetWrapper() {
        TestServiceImpl service = new TestServiceImpl();
        MethodHandleProviderDispatcher<TestService> dispatcher =
                MethodHandleProviderDispatcher.create(TestService.class, service);

        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                dispatcher.invoke(service, "fail", new Class<?>[0], new Object[0]));

        assertEquals("business-failure", failure.getMessage());
    }

    @Test
    void rejectsUnknownSignatures() {
        TestServiceImpl service = new TestServiceImpl();
        MethodHandleProviderDispatcher<TestService> dispatcher =
                MethodHandleProviderDispatcher.create(TestService.class, service);

        assertThrows(NoSuchMethodException.class, () -> dispatcher.invoke(
                service,
                "find",
                new Class<?>[]{int.class},
                new Object[]{1}));
    }

    public interface TestService {
        int add(int left, int right);

        String find(long id);

        String find(String name);

        void reset();

        void fail();
    }

    private static final class TestServiceImpl implements TestService {
        private int resetCount;

        @Override
        public int add(int left, int right) {
            return left + right;
        }

        @Override
        public String find(long id) {
            return "id=" + id;
        }

        @Override
        public String find(String name) {
            return "name=" + name;
        }

        @Override
        public void reset() {
            resetCount++;
        }

        @Override
        public void fail() {
            throw new IllegalStateException("business-failure");
        }
    }
}
