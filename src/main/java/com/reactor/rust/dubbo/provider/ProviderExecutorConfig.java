package com.reactor.rust.dubbo.provider;

import java.util.Locale;
import java.util.Set;

/**
 * Bounded Dubbo provider executor settings mapped directly to the provider URL.
 */
public record ProviderExecutorConfig(
        String threadPool,
        int coreThreads,
        int maxThreads,
        int queueCapacity,
        int idleTimeoutMs,
        int ioThreads) {

    private static final Set<String> SUPPORTED_THREAD_POOLS = Set.of("eager", "fixed", "limited");

    public ProviderExecutorConfig {
        threadPool = threadPool == null ? "" : threadPool.trim().toLowerCase(Locale.ROOT);
        if (threadPool.isEmpty()) {
            if (coreThreads != 0 || maxThreads != 0 || queueCapacity != 0 || idleTimeoutMs != 0 || ioThreads != 0) {
                throw new IllegalArgumentException("Unconfigured provider executor cannot contain limits");
            }
        } else {
            if (!SUPPORTED_THREAD_POOLS.contains(threadPool)) {
                throw new IllegalArgumentException("Unsupported provider thread pool: " + threadPool);
            }
            if (coreThreads < 1) {
                throw new IllegalArgumentException("coreThreads must be >= 1");
            }
            if (maxThreads < coreThreads) {
                throw new IllegalArgumentException("maxThreads must be >= coreThreads");
            }
            if (queueCapacity < 0) {
                throw new IllegalArgumentException("queueCapacity must be >= 0");
            }
            if (idleTimeoutMs < 1) {
                throw new IllegalArgumentException("idleTimeoutMs must be >= 1");
            }
            if (ioThreads < 1) {
                throw new IllegalArgumentException("ioThreads must be >= 1");
            }
        }
    }

    public static ProviderExecutorConfig unconfigured() {
        return new ProviderExecutorConfig("", 0, 0, 0, 0, 0);
    }

    public boolean configured() {
        return !threadPool.isEmpty();
    }
}
