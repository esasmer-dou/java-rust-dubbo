package com.reactor.rust.dubbo.internal.util;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CoalescingTaskTest {

    @Test
    void keepsOneQueuedTaskAndOneCoalescedRerun() {
        ManualExecutor executor = new ManualExecutor();
        AtomicInteger runs = new AtomicInteger();
        AtomicReference<CoalescingTask> taskRef = new AtomicReference<>();
        CoalescingTask task = new CoalescingTask(executor, () -> {
            if (runs.incrementAndGet() == 1) {
                taskRef.get().request();
                taskRef.get().request();
            }
        });
        taskRef.set(task);

        task.request();
        task.request();
        task.request();
        assertEquals(1, executor.size());

        executor.runNext();
        assertEquals(1, runs.get());
        assertEquals(1, executor.size());

        executor.runNext();
        assertEquals(2, runs.get());
        assertEquals(0, executor.size());
    }

    @Test
    void rejectedSubmissionReturnsToIdleAndCanBeRetried() {
        ManualExecutor executor = new ManualExecutor();
        AtomicInteger runs = new AtomicInteger();
        CoalescingTask task = new CoalescingTask(executor, runs::incrementAndGet);
        executor.reject = true;

        assertThrows(RejectedExecutionException.class, task::request);
        executor.reject = false;
        task.request();
        executor.runNext();

        assertEquals(1, runs.get());
    }

    private static final class ManualExecutor implements Executor {

        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        private boolean reject;

        @Override
        public void execute(Runnable command) {
            if (reject) {
                throw new RejectedExecutionException("test-rejection");
            }
            tasks.addLast(command);
        }

        int size() {
            return tasks.size();
        }

        void runNext() {
            tasks.removeFirst().run();
        }
    }
}
