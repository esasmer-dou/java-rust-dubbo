package com.reactor.rust.dubbo.internal.registry;

import com.reactor.rust.dubbo.DubboConsumerConfig;
import com.reactor.rust.dubbo.DubboConsumerException;
import com.reactor.rust.dubbo.internal.util.CoalescingTask;

import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class ZookeeperRegistryClient implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(ZookeeperRegistryClient.class.getName());

    private final DubboConsumerConfig config;
    private final ScheduledExecutorService scheduler;
    private final Set<Runnable> refreshListeners = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean();
    private final AtomicInteger reconnectAttempt = new AtomicInteger();
    private final AtomicLong generation = new AtomicLong();
    private final CoalescingTask refreshTask;

    private volatile ZooKeeper zookeeper;
    private volatile CountDownLatch connectedLatch = new CountDownLatch(1);
    private volatile boolean connected;
    private volatile boolean closed;
    private volatile DubboConsumerException terminalFailure;
    private volatile String lastFailure = "";

    public ZookeeperRegistryClient(DubboConsumerConfig config, ScheduledExecutorService scheduler) {
        this.config = config;
        this.scheduler = scheduler;
        this.refreshTask = new CoalescingTask(scheduler, this::notifyRefreshListeners);
    }

    public void start() {
        try {
            openNewSession();
        } catch (RuntimeException failure) {
            if (config.registryCheck()) {
                throw failure;
            }
            lastFailure = failure.getMessage() == null
                    ? failure.getClass().getName()
                    : failure.getMessage();
            scheduleReconnect(nextReconnectDelayMs());
            return;
        }
        if (config.registryCheck()) {
            awaitConnected();
        }
    }

    public List<String> getChildren(String path, Watcher watcher) throws Exception {
        return current().getChildren(path, watcher);
    }

    public Stat exists(String path, Watcher watcher) throws Exception {
        return current().exists(path, watcher);
    }

    public void registerRefresh(Runnable listener) {
        refreshListeners.add(listener);
    }

    public void unregisterRefresh(Runnable listener) {
        refreshListeners.remove(listener);
    }

    public boolean isConnected() {
        return connected && !closed && terminalFailure == null;
    }

    public long generation() {
        return generation.get();
    }

    public String lastFailure() {
        return lastFailure;
    }

    @Override
    public void close() {
        ZooKeeper current;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            connected = false;
            refreshListeners.clear();
            generation.incrementAndGet();
            current = zookeeper;
            zookeeper = null;
        }
        closeQuietly(current);
    }

    private ZooKeeper current() {
        DubboConsumerException failure = terminalFailure;
        if (failure != null) {
            throw failure;
        }
        ZooKeeper current = zookeeper;
        if (current == null || !connected || !current.getState().isAlive()) {
            throw new DubboConsumerException("Zookeeper client is not connected");
        }
        return current;
    }

    private void openNewSession() {
        ZooKeeper previous;
        synchronized (this) {
            if (closed || terminalFailure != null) {
                return;
            }
            long nextGeneration = generation.incrementAndGet();
            connected = false;
            connectedLatch = new CountDownLatch(1);
            SessionWatcher watcher = new SessionWatcher(nextGeneration);
            ZooKeeper created;
            try {
                created = new ZooKeeper(
                        RegistryAddress.zookeeperConnectString(config.registryAddress()),
                        config.registrySessionTimeoutMs(),
                        watcher);
                if (config.registryAuthenticationEnabled()) {
                    created.addAuthInfo(
                            config.registryAuthScheme(),
                            config.registryAuthData().getBytes(StandardCharsets.UTF_8));
                }
            } catch (IOException | RuntimeException e) {
                lastFailure = e.getMessage() == null ? e.getClass().getName() : e.getMessage();
                throw new DubboConsumerException(
                        "Failed to create Zookeeper client for " + config.registryAddress(),
                        e);
            }
            previous = zookeeper;
            zookeeper = created;
            watcher.activate();
        }
        closeQuietly(previous);
    }

    private void onSessionEvent(long eventGeneration, WatchedEvent event) {
        if (closed || eventGeneration != generation.get()) {
            return;
        }
        Watcher.Event.KeeperState state = event.getState();
        if (state == Watcher.Event.KeeperState.SyncConnected
                || state == Watcher.Event.KeeperState.ConnectedReadOnly) {
            connected = true;
            lastFailure = "";
            reconnectAttempt.set(0);
            connectedLatch.countDown();
            requestRefresh();
            return;
        }
        if (state == Watcher.Event.KeeperState.Disconnected) {
            connected = false;
            lastFailure = "Zookeeper connection is temporarily disconnected";
            return;
        }
        if (state == Watcher.Event.KeeperState.Expired) {
            connected = false;
            lastFailure = "Zookeeper session expired";
            scheduleReconnect(0L);
            return;
        }
        if (state == Watcher.Event.KeeperState.AuthFailed) {
            connected = false;
            terminalFailure = new DubboConsumerException(
                    "Zookeeper authentication failed for " + config.registryAddress());
            lastFailure = terminalFailure.getMessage();
            connectedLatch.countDown();
        }
    }

    private void scheduleReconnect(long delayMs) {
        if (closed || terminalFailure != null || !reconnectScheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            scheduler.schedule(() -> {
                reconnectScheduled.set(false);
                if (closed || terminalFailure != null) {
                    return;
                }
                try {
                    openNewSession();
                } catch (RuntimeException e) {
                    lastFailure = e.getMessage() == null ? e.getClass().getName() : e.getMessage();
                    LOG.log(System.Logger.Level.WARNING, "Zookeeper reconnect failed: {0}", lastFailure);
                    scheduleReconnect(nextReconnectDelayMs());
                }
            }, delayMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            reconnectScheduled.set(false);
            if (!closed) {
                throw e;
            }
        }
    }

    private long nextReconnectDelayMs() {
        int attempt = Math.min(reconnectAttempt.getAndIncrement(), 20);
        long initial = config.registryReconnectInitialDelayMs();
        long multiplier = 1L << attempt;
        long candidate = initial > Long.MAX_VALUE / multiplier
                ? Long.MAX_VALUE
                : initial * multiplier;
        return Math.min(candidate, config.registryReconnectMaxDelayMs());
    }

    private void awaitConnected() {
        CountDownLatch latch = connectedLatch;
        try {
            boolean signalled = latch.await(config.registryTimeoutMs(), TimeUnit.MILLISECONDS);
            DubboConsumerException failure = terminalFailure;
            if (failure != null) {
                throw failure;
            }
            if (!signalled || !connected) {
                throw new DubboConsumerException("Timed out connecting to Zookeeper " + config.registryAddress());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DubboConsumerException("Interrupted while connecting to Zookeeper " + config.registryAddress(), e);
        }
    }

    private void requestRefresh() {
        try {
            refreshTask.request();
        } catch (RejectedExecutionException e) {
            if (!closed) {
                throw e;
            }
        }
    }

    private void notifyRefreshListeners() {
        for (Runnable listener : refreshListeners) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                lastFailure = e.getMessage() == null ? e.getClass().getName() : e.getMessage();
                LOG.log(System.Logger.Level.WARNING, "Dubbo provider refresh failed: {0}", lastFailure);
            }
        }
    }

    private static void closeQuietly(ZooKeeper current) {
        if (current == null) {
            return;
        }
        try {
            current.close();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private final class SessionWatcher implements Watcher {

        private final long sessionGeneration;
        private WatchedEvent pending;
        private boolean active;

        private SessionWatcher(long sessionGeneration) {
            this.sessionGeneration = sessionGeneration;
        }

        @Override
        public void process(WatchedEvent event) {
            synchronized (this) {
                if (!active) {
                    pending = event;
                    return;
                }
            }
            onSessionEvent(sessionGeneration, event);
        }

        private void activate() {
            WatchedEvent event;
            synchronized (this) {
                active = true;
                event = pending;
                pending = null;
            }
            if (event != null) {
                onSessionEvent(sessionGeneration, event);
            }
        }
    }
}
