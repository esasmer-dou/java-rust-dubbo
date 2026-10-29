package com.reactor.rust.dubbo.provider;

import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.reactor.rust.dubbo.provider.NativeWire.check;

/** Separate JVM because native registration is intentionally one-shot. */
public final class NativeDeadlineProbe {
    public static void main(String[] ignored) throws Exception {
        int port; try (var free = new ServerSocket(0)) { port = free.getLocalPort(); }
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var completed = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        var provider = NativeProviderFactory.start(new NativeProviderFactory.Options(port,1,1,1,128*1024,3000,0,64),
                List.of(CatalogDefinition__NativeProvider.bind(() -> {
                    calls.incrementAndGet(); entered.countDown();
                    try { check(release.await(5,TimeUnit.SECONDS), "late callback release deadline"); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                    finally { completed.countDown(); }
                    // Serialization and native response growth happen AFTER zero-deadline close.
                    return new byte[70_000];
                }, PlainDubboProvider.ServiceExecutionConfig.bounded(1))), null);
        try (ExecutorService wire = Executors.newSingleThreadExecutor()) {
            var request = wire.submit(() -> {
                try { return NativeWire.call(port, CatalogDefinition__NativeProvider.CONTRACT,"getNestedCatalogJson"); }
                catch (java.io.IOException expectedDuringShutdown) { return null; }
            });
            check(entered.await(3,TimeUnit.SECONDS), "late callback entered");
            long start = System.nanoTime(); provider.close();
            check(System.nanoTime()-start < TimeUnit.SECONDS.toNanos(2), "close deadline bounded");
            check(completed.getCount() == 1, "close did not forcibly terminate Java callback");
            release.countDown(); check(completed.await(3,TimeUnit.SECONDS), "late callback completed");
            request.get(5,TimeUnit.SECONDS); provider.close();
            check(calls.get() == 1, "no hidden replay");
            // Keep the JVM alive until the callback also finishes serialization/return, not only business code.
            long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while (provider.activeCodecContexts() != 0 && System.nanoTime() < deadline) Thread.sleep(5);
            check(provider.activeCodecContexts() == 0, "late callback finally detached");
            System.out.println("DEADLINE_CLOSE_PASS calls="+calls);
        } finally { release.countDown(); provider.close(); }
    }
}
