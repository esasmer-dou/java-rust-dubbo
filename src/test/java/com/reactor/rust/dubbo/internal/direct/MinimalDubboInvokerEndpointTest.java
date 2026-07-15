package com.reactor.rust.dubbo.internal.direct;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.rpc.AppResponse;
import org.apache.dubbo.rpc.AsyncRpcResult;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcInvocation;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MinimalDubboInvokerEndpointTest {

    @Test
    void retirementWaitsForAnInflightAsyncInvocation() {
        CompletableFuture<AppResponse> response = new CompletableFuture<>();
        TestInvoker invoker = new TestInvoker(response);
        MinimalDubboInvoker.Endpoint<TestService> endpoint =
                new MinimalDubboInvoker.Endpoint<>("127.0.0.1:20880", invoker);

        endpoint.invoke(invocation());
        endpoint.retire();

        assertEquals(0, invoker.destroyCount.get());

        response.complete(new AppResponse("ok"));

        assertEquals(1, invoker.destroyCount.get());
        endpoint.retire();
        assertEquals(1, invoker.destroyCount.get());
    }

    @Test
    void retiredEndpointRejectsNewInvocationsAndDestroysOnce() {
        TestInvoker invoker = new TestInvoker(new CompletableFuture<>());
        MinimalDubboInvoker.Endpoint<TestService> endpoint =
                new MinimalDubboInvoker.Endpoint<>("127.0.0.1:20880", invoker);

        endpoint.retire();

        assertThrows(RpcException.class, () -> endpoint.invoke(invocation()));
        assertEquals(1, invoker.destroyCount.get());
        endpoint.retire();
        assertEquals(1, invoker.destroyCount.get());
    }

    private static Invocation invocation() {
        return new RpcInvocation("find", TestService.class.getName(), "", new Class<?>[0], new Object[0]);
    }

    private interface TestService {
        String find();
    }

    private static final class TestInvoker implements Invoker<TestService> {
        private final CompletableFuture<AppResponse> response;
        private final AtomicInteger destroyCount = new AtomicInteger();

        private TestInvoker(CompletableFuture<AppResponse> response) {
            this.response = response;
        }

        @Override
        public Class<TestService> getInterface() {
            return TestService.class;
        }

        @Override
        public URL getUrl() {
            return URL.valueOf("dubbo://127.0.0.1:20880/" + TestService.class.getName());
        }

        @Override
        public boolean isAvailable() {
            return destroyCount.get() == 0;
        }

        @Override
        public Result invoke(Invocation invocation) throws RpcException {
            return new AsyncRpcResult(response, invocation);
        }

        @Override
        public void destroy() {
            destroyCount.incrementAndGet();
        }
    }
}
