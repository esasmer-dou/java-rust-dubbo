package com.reactor.rust.dubbo.internal.util;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public final class NamedDaemonThreadFactory implements ThreadFactory {

    private static final long DEFAULT_STACK_SIZE_BYTES = 256L * 1024L;

    private final String prefix;
    private final long stackSizeBytes;
    private final AtomicInteger sequence = new AtomicInteger();

    public NamedDaemonThreadFactory(String prefix) {
        this(prefix, DEFAULT_STACK_SIZE_BYTES);
    }

    public NamedDaemonThreadFactory(String prefix, long stackSizeBytes) {
        this.prefix = prefix;
        if (stackSizeBytes < 0) {
            throw new IllegalArgumentException("stackSizeBytes must be non-negative");
        }
        this.stackSizeBytes = stackSizeBytes;
    }

    @Override
    public Thread newThread(Runnable runnable) {
        Thread thread = new Thread(
                null,
                runnable,
                prefix + '-' + sequence.incrementAndGet(),
                stackSizeBytes,
                false);
        thread.setDaemon(true);
        return thread;
    }
}
