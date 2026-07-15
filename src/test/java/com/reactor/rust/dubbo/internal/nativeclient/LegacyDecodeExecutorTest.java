package com.reactor.rust.dubbo.internal.nativeclient;

import com.reactor.rust.dubbo.DubboConsumerConfig;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyDecodeExecutorTest {

    @Test
    void startsLazilyRunsOffCallerThreadAndRejectsAfterClose() throws Exception {
        DubboConsumerConfig config = DubboConsumerConfig.builder()
                .nativeAsyncWorkers(1)
                .nativeAsyncQueueCapacity(4)
                .build();

        LegacyDecodeExecutor executor = LegacyDecodeExecutor.forConfig(config);
        assertFalse(executor.started());

        String caller = Thread.currentThread().getName();
        String worker = CompletableFuture.supplyAsync(() -> Thread.currentThread().getName(), executor)
                .get(2, TimeUnit.SECONDS);

        assertTrue(executor.started());
        assertTrue(worker.startsWith("reactor-dubbo-hessian-decode-"));
        assertFalse(caller.equals(worker));

        executor.close();
        assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> { }));
    }

    @Test
    void separateClientsDoNotShareLifecycle() {
        LegacyDecodeExecutor first = LegacyDecodeExecutor.forLimits(1, 2);
        LegacyDecodeExecutor second = LegacyDecodeExecutor.forLimits(1, 2);

        first.close();

        assertThrows(RejectedExecutionException.class, () -> first.execute(() -> { }));
        second.execute(() -> { });
        second.close();
    }
}
