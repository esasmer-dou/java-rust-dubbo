package com.reactor.rust.dubbo.internal.util;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/** Keeps at most one queued/running task plus one coalesced rerun. */
public final class CoalescingTask {

    private static final int IDLE = 0;
    private static final int RUNNING = 1;
    private static final int RERUN = 2;

    private final Executor executor;
    private final Runnable task;
    private final AtomicInteger state = new AtomicInteger(IDLE);

    public CoalescingTask(Executor executor, Runnable task) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.task = Objects.requireNonNull(task, "task");
    }

    public void request() {
        for (;;) {
            int current = state.get();
            if (current == IDLE) {
                if (state.compareAndSet(IDLE, RUNNING)) {
                    submit();
                    return;
                }
                continue;
            }
            if (current == RUNNING) {
                state.compareAndSet(RUNNING, RERUN);
            }
            return;
        }
    }

    private void submit() {
        try {
            executor.execute(this::drain);
        } catch (RejectedExecutionException e) {
            state.set(IDLE);
            throw e;
        }
    }

    private void drain() {
        Throwable failure = null;
        try {
            task.run();
        } catch (RuntimeException | Error e) {
            failure = e;
        }

        boolean rerun = state.getAndSet(IDLE) == RERUN;
        if (rerun) {
            request();
        }
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }
}
