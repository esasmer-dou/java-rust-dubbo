package com.reactor.rust.dubbo.internal.nativeclient;

import com.reactor.rust.dubbo.DubboConsumerConfig;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Client-owned, lazy and bounded executor for legacy Java Hessian response decoding.
 * Native byte-array and native response-handle paths never start its worker threads.
 */
public final class LegacyDecodeExecutor implements Executor, AutoCloseable {

    private static final long THREAD_STACK_BYTES = 256L * 1024L;

    private final int workers;
    private final int queueCapacity;
    private volatile ThreadPoolExecutor delegate;
    private volatile boolean closed;

    private LegacyDecodeExecutor(int workers, int queueCapacity) {
        if (workers < 1) {
            throw new IllegalArgumentException("workers must be >= 1");
        }
        if (queueCapacity < 1) {
            throw new IllegalArgumentException("queueCapacity must be >= 1");
        }
        this.workers = workers;
        this.queueCapacity = queueCapacity;
    }

    public static LegacyDecodeExecutor forConfig(DubboConsumerConfig config) {
        Objects.requireNonNull(config, "config");
        return new LegacyDecodeExecutor(config.nativeAsyncWorkers(), config.nativeAsyncQueueCapacity());
    }

    public static LegacyDecodeExecutor forLimits(int workers, int queueCapacity) {
        return new LegacyDecodeExecutor(workers, queueCapacity);
    }

    @Override
    public void execute(Runnable command) {
        Objects.requireNonNull(command, "command");
        executor().execute(command);
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        ThreadPoolExecutor current = delegate;
        delegate = null;
        if (current != null) {
            current.shutdown();
        }
    }

    boolean started() {
        return delegate != null;
    }

    private ThreadPoolExecutor executor() {
        ThreadPoolExecutor current = delegate;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (closed) {
                throw new RejectedExecutionException("Legacy Dubbo decode executor is closed");
            }
            current = delegate;
            if (current == null) {
                current = create();
                delegate = current;
            }
            return current;
        }
    }

    private ThreadPoolExecutor create() {
        AtomicInteger ids = new AtomicInteger();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                workers,
                workers,
                30L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                task -> {
                    Thread thread = new Thread(
                            null,
                            task,
                            "reactor-dubbo-hessian-decode-" + ids.incrementAndGet(),
                            THREAD_STACK_BYTES);
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }
}
