package com.reactor.rust.dubbo.provider;

import com.reactor.rust.dubbo.sample.CustomerCommandService;
import com.reactor.rust.dubbo.sample.dto.*;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.reactor.rust.dubbo.provider.NativeWire.check;

/** Runs without JUnit, Spring, Apache Dubbo, Netty, Hessian Lite or the processor on its class path. */
public final class NativeRpcProbe {
    private static final AtomicInteger CALLS = new AtomicInteger();
    private static final AtomicInteger DECORATED = new AtomicInteger();
    private static final AtomicInteger WRAPS = new AtomicInteger();
    private static final CountDownLatch ENTERED = new CountDownLatch(1), RELEASE = new CountDownLatch(1);
    private static final CountDownLatch SECOND_ENTERED = new CountDownLatch(1), SECOND_RELEASE = new CountDownLatch(1);
    private static final byte[] CATALOG = "{\"city\":\"\u0130stanbul\",\"emoji\":\"\ud83d\ude80\"}".getBytes(StandardCharsets.UTF_8);

    public static void main(String[] ignored) throws Exception {
        int port;
        try (var free = new ServerSocket(0)) { port = free.getLocalPort(); }
        var customer = new Customer();
        var options = new NativeProviderFactory.Options(port, 1, 4, 4, 128 * 1024, 4000, 4000, 1024);
        var catalogContract = CatalogDefinition__NativeProvider.CONTRACT;
        var commandContract = CustomerDefinition__NativeProvider.CONTRACT;
        ProviderDispatcherDecorator decorator = new ProviderDispatcherDecorator() {
            @Override public <T> ProviderMethodDispatcher<T> decorate(Class<T> type, ProviderMethodDispatcher<T> dispatcher) {
                WRAPS.incrementAndGet();
                return (target, name, types, args) -> { DECORATED.incrementAndGet(); return dispatcher.invoke(target, name, types, args); };
            }
        };
        var provider = NativeProviderFactory.start(options, List.of(
                CatalogDefinition__NativeProvider.bind(() -> { CALLS.incrementAndGet(); return CATALOG; }, PlainDubboProvider.ServiceExecutionConfig.bounded(1)),
                CustomerDefinition__NativeProvider.bind(customer, PlainDubboProvider.ServiceExecutionConfig.bounded(2,
                        Map.of("patchCustomerStatusTyped", 1))),
                ProbeDefinition__NativeProvider.bind(new NativeFixtures.ProbeService() {
                    public NativeFixtures.Page echo(NativeFixtures.Page page) { CALLS.incrementAndGet(); return page; }
                    public double fraction(double value) { CALLS.incrementAndGet(); return value; }
                    public void notify(String message) { CALLS.incrementAndGet(); }
                    public NativeFixtures.Scalars scalars(NativeFixtures.Scalars value) { CALLS.incrementAndGet(); return value; }
                }, PlainDubboProvider.ServiceExecutionConfig.bounded(1))), decorator);
        try {
            check(WRAPS.get() == 3, "decorate once per service");
            check(Arrays.equals(CATALOG, (byte[]) success(NativeWire.call(port, catalogContract, "getNestedCatalogJson"))), "Catalog bytes");
            byte[] json = "{\"name\":\"\u00c7a\u011fr\u0131\ud83d\ude80\"}".getBytes(StandardCharsets.UTF_8);
            for (String name : List.of("createCustomer", "patchCustomerSegment", "patchCustomerStatus", "deleteCustomer")) {
                Object[] args = name.equals("createCustomer") ? new Object[]{json} : new Object[]{42L, json};
                check(Arrays.equals(json, (byte[]) success(NativeWire.call(port, commandContract, name, args))), name);
            }
            var command = new CreateCustomerCommand("C42", "\u0130pek \ud83d\ude80", "VIP", "i@example.test", "r1");
            var result = (CustomerMutationResult) success(NativeWire.call(port, commandContract, "createCustomerTyped", command));
            check(result.success() && result.customerId() == 42L && result.fullName().equals(command.fullName()) && result.requestId().equals("r1"), "typed command fields");
            check(success(NativeWire.call(port, commandContract, "patchCustomerStatusTyped", 42L, "ACTIVE", "r2")) instanceof CustomerMutationResult, "typed patch");
            marker("catalogAndAllCustomerMethodsRoundTrip");

            byte[] large = new byte[70_000]; Arrays.fill(large, (byte)0xe7);
            check(Arrays.equals(large, (byte[])success(NativeWire.call(port, commandContract, "createCustomer", large))), "chunked binary and response growth");
            var scaled = NativeWire.call(port, ProbeDefinition__NativeProvider.CONTRACT, "fraction",
                    new byte[]{0x5f,0,0,4,(byte)0xd2}, new Object[0]);
            check(success(scaled).equals(1.234d), "real native scanner accepts Hessian 0x5f");
            check(Double.doubleToRawLongBits((double)success(NativeWire.call(port, ProbeDefinition__NativeProvider.CONTRACT,
                    "fraction", -0.0d))) == Long.MIN_VALUE, "negative zero wire sign");
            check(NativeWire.call(port, ProbeDefinition__NativeProvider.CONTRACT, "notify", "void").flag() == 2, "void response");
            marker("chunkedGrowthScaledDoubleAndVoid");

            int before = CALLS.get();
            var bad = NativeWire.call(port, commandContract, "createCustomer", new byte[]{'B', 0x7f, (byte)0xff}, new Object[0]);
            check(bad.status() != 20 || bad.flag() == 0, "malformed binary rejected");
            check(CALLS.get() == before, "malformed did not invoke");
            success(NativeWire.call(port, commandContract, "createCustomer", json));
            check(NativeWire.call(port, commandContract, "patchCustomerStatusTyped", 42L, "FAIL", "r3").flag() == 0, "business error encoded");
            success(NativeWire.call(port, commandContract, "patchCustomerStatusTyped", 42L, "ACTIVE", "r4"));
            marker("malformedAndBusinessFailureRelease");

            ExecutorService threads = Executors.newFixedThreadPool(2);
            try {
                var blocked = threads.submit(() -> NativeWire.call(port, commandContract, "patchCustomerStatusTyped", 42L, "BLOCK", "r5"));
                check(ENTERED.await(3, TimeUnit.SECONDS), "blocked method entered");
                before = DECORATED.get();
                var rejected = NativeWire.call(port, commandContract, "patchCustomerStatusTyped", 42L, "ACTIVE", "r6");
                check(rejected.flag() == 0, "method cap rejected");
                check(DECORATED.get() == before, "cap runs before decorator/business");
                success(NativeWire.call(port, commandContract, "createCustomer", json));
                var second = threads.submit(() -> NativeWire.call(port, commandContract, "createCustomer", new byte[]{(byte)0xff}));
                check(SECOND_ENTERED.await(3, TimeUnit.SECONDS), "second service permit entered");
                before = DECORATED.get();
                check(NativeWire.call(port, commandContract, "deleteCustomer", 42L, json).flag() == 0, "service cap rejected");
                check(DECORATED.get() == before, "service cap before decorator/business");
                // Different service remains independent while one command permit is held.
                success(NativeWire.call(port, catalogContract, "getNestedCatalogJson"));
                SECOND_RELEASE.countDown(); check(second.get(3, TimeUnit.SECONDS).flag() == 1, "second permit released");
                marker("methodCapAndServiceIsolation");
                var close = threads.submit(provider::close);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (!provider.isClosing() && System.nanoTime() < deadline) Thread.onSpinWait();
                check(provider.isClosing(), "close published");
                check(!close.isDone(), "close waits for active callback");
                RELEASE.countDown();
                check(blocked.get(3, TimeUnit.SECONDS).flag() == 1, "admitted RPC completed during drain");
                close.get(5, TimeUnit.SECONDS); provider.close();
                check(CALLS.get() == DECORATED.get(), "all business calls decorated exactly once");
                check(WRAPS.get() == 3, "no retry or redecorate");
                marker("drainAndIdempotentClose");
            } finally { RELEASE.countDown(); SECOND_RELEASE.countDown(); threads.shutdownNow(); check(threads.awaitTermination(5, TimeUnit.SECONDS), "test threads stopped"); }
        } finally { RELEASE.countDown(); provider.close(); }
        try { NativeProviderFactory.start(options, List.of(CatalogDefinition__NativeProvider.bind(() -> CATALOG,
                PlainDubboProvider.ServiceExecutionConfig.bounded(1))), null); throw new AssertionError("restart accepted"); }
        catch (IllegalStateException expected) { check(expected.getMessage().contains("one-shot"), "one-shot explained"); }
        marker("oneShotRegistryExplicit");
        System.out.println("NATIVE_RPC_PASS calls=" + CALLS + " decorated=" + DECORATED);
    }
    private static Object success(NativeWire.Reply reply) {
        check(reply.status() == 20 && reply.flag() != 0, "RPC success: " + reply); return reply.value();
    }
    private static void marker(String name) { System.out.println("PROOF_PASS " + name); }
    private static final class Customer implements CustomerCommandService {
        @Override public byte[] createCustomer(byte[] bytes) {
            CALLS.incrementAndGet();
            if (bytes.length == 1 && bytes[0] == (byte)0xff) {
                SECOND_ENTERED.countDown();
                try { check(SECOND_RELEASE.await(5, TimeUnit.SECONDS), "second release deadline"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            }
            return bytes;
        }
        @Override public byte[] patchCustomerSegment(long id, byte[] bytes) { return createCustomer(bytes); }
        @Override public byte[] patchCustomerStatus(long id, byte[] bytes) { return createCustomer(bytes); }
        @Override public byte[] deleteCustomer(long id, byte[] bytes) { return createCustomer(bytes); }
        @Override public CustomerMutationResult createCustomerTyped(CreateCustomerCommand c) {
            CALLS.incrementAndGet(); return result(c.fullName(), c.requestId());
        }
        @Override public CustomerMutationResult patchCustomerStatusTyped(long id, String status, String request) {
            CALLS.incrementAndGet();
            if (status.equals("FAIL")) throw new IllegalArgumentException("business failure");
            if (status.equals("BLOCK")) {
                ENTERED.countDown();
                try { check(RELEASE.await(5, TimeUnit.SECONDS), "release deadline"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            }
            return result("\u0130pek \ud83d\ude80", request);
        }
        private CustomerMutationResult result(String name, String request) {
            return new CustomerMutationResult("CREATE", request, true, 42L, "C42", name, "VIP", "ACTIVE", null, "2026-10-21");
        }
    }
}
