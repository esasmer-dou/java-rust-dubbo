package com.reactor.rust.dubbo;

import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

final class ZookeeperRegistryClient implements AutoCloseable, Watcher {

    private final DubboConsumerConfig config;
    private final Executor refreshExecutor;
    private final Set<Runnable> refreshListeners = ConcurrentHashMap.newKeySet();
    private volatile ZooKeeper zookeeper;
    private volatile CountDownLatch connectedLatch = new CountDownLatch(1);
    private volatile boolean closed;

    ZookeeperRegistryClient(DubboConsumerConfig config, Executor refreshExecutor) {
        this.config = config;
        this.refreshExecutor = refreshExecutor;
    }

    void start() {
        reconnect();
        if (config.registryCheck()) {
            awaitConnected();
        }
    }

    List<String> getChildren(String path, Watcher watcher) throws Exception {
        return current().getChildren(path, watcher);
    }

    Stat exists(String path, Watcher watcher) throws Exception {
        return current().exists(path, watcher);
    }

    void registerRefresh(Runnable listener) {
        refreshListeners.add(listener);
    }

    void unregisterRefresh(Runnable listener) {
        refreshListeners.remove(listener);
    }

    @Override
    public void process(WatchedEvent event) {
        if (closed) {
            return;
        }
        Watcher.Event.KeeperState state = event.getState();
        if (state == Watcher.Event.KeeperState.SyncConnected
                || state == Watcher.Event.KeeperState.ConnectedReadOnly) {
            connectedLatch.countDown();
            fireRefresh();
        } else if (state == Watcher.Event.KeeperState.Expired) {
            refreshExecutor.execute(this::reconnectAndRefresh);
        }
    }

    @Override
    public void close() {
        closed = true;
        refreshListeners.clear();
        ZooKeeper current = zookeeper;
        zookeeper = null;
        if (current != null) {
            try {
                current.close();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private ZooKeeper current() {
        ZooKeeper current = zookeeper;
        if (current == null) {
            throw new DubboConsumerException("Zookeeper client is not connected");
        }
        return current;
    }

    private synchronized void reconnectAndRefresh() {
        if (closed) {
            return;
        }
        reconnect();
        fireRefresh();
    }

    private synchronized void reconnect() {
        if (closed) {
            return;
        }
        ZooKeeper previous = zookeeper;
        connectedLatch = new CountDownLatch(1);
        try {
            zookeeper = new ZooKeeper(
                    RegistryAddress.zookeeperConnectString(config.registryAddress()),
                    config.registrySessionTimeoutMs(),
                    this);
        } catch (IOException e) {
            throw new DubboConsumerException("Failed to create Zookeeper client for " + config.registryAddress(), e);
        }
        if (previous != null) {
            try {
                previous.close();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void awaitConnected() {
        try {
            boolean connected = connectedLatch.await(config.registryTimeoutMs(), TimeUnit.MILLISECONDS);
            if (!connected) {
                throw new DubboConsumerException("Timed out connecting to Zookeeper " + config.registryAddress());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DubboConsumerException("Interrupted while connecting to Zookeeper " + config.registryAddress(), e);
        }
    }

    private void fireRefresh() {
        for (Runnable listener : refreshListeners) {
            refreshExecutor.execute(listener);
        }
    }
}
