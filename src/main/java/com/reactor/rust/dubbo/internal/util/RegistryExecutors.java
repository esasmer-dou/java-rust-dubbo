package com.reactor.rust.dubbo.internal.util;

import com.reactor.rust.dubbo.DubboConsumerConfig;

import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class RegistryExecutors {

    private RegistryExecutors() {
    }

    public static ScheduledThreadPoolExecutor create(DubboConsumerConfig config, String threadPrefix) {
        int threads = Math.max(1, config.referThreadNum());
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(
                threads,
                new NamedDaemonThreadFactory(threadPrefix));
        executor.setKeepAliveTime(30L, TimeUnit.SECONDS);
        executor.allowCoreThreadTimeOut(true);
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        return executor;
    }
}
