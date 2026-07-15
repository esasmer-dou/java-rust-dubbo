package com.reactor.rust.dubbo.internal.direct;

import com.reactor.rust.dubbo.DubboConsumerConfig;
import com.reactor.rust.dubbo.DubboConsumerException;
import com.reactor.rust.dubbo.DubboReferenceSpec;
import com.reactor.rust.dubbo.internal.registry.DubboUrlFactory;
import com.reactor.rust.dubbo.internal.registry.ProviderWatcher;
import com.reactor.rust.dubbo.internal.registry.ZookeeperRegistryClient;
import com.reactor.rust.dubbo.internal.runtime.DubboRuntimeModel;
import com.reactor.rust.dubbo.internal.util.CoalescingTask;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Protocol;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

final class ZookeeperProviderWatcher<T> implements ProviderWatcher {

    private final DubboConsumerConfig config;
    private final DubboReferenceSpec<T> spec;
    private final ZookeeperRegistryClient zookeeper;
    private final MinimalDubboInvoker<T> invoker;
    private final Protocol protocol;
    private final String providerPath;
    private final Watcher watcher = this::onWatchedEvent;
    private final CoalescingTask refreshTask;
    private final Runnable reconnectRefresh;
    private volatile Map<String, MinimalDubboInvoker.Endpoint<T>> endpointsByKey = Map.of();
    private volatile boolean closed;

    ZookeeperProviderWatcher(
            DubboConsumerConfig config,
            DubboReferenceSpec<T> spec,
            ZookeeperRegistryClient zookeeper,
            Executor refreshExecutor,
            MinimalDubboInvoker<T> invoker) {
        this.config = config;
        this.spec = spec;
        this.zookeeper = zookeeper;
        this.invoker = invoker;
        this.providerPath = DubboUrlFactory.providerPath(config, spec);
        this.refreshTask = new CoalescingTask(refreshExecutor, this::refreshSync);
        this.reconnectRefresh = refreshTask::request;
        ExtensionLoader<Protocol> loader = DubboRuntimeModel.module().getExtensionLoader(Protocol.class);
        this.protocol = loader.getExtension(config.protocol(), false);
    }

    public void start() {
        zookeeper.registerRefresh(reconnectRefresh);
        try {
            refreshSync();
        } catch (RuntimeException | LinkageError failure) {
            closed = true;
            zookeeper.unregisterRefresh(reconnectRefresh);
            invoker.replaceEndpoints(emptyEndpoints());
            endpointsByKey = Map.of();
            throw failure;
        }
    }

    @Override
    public void close() {
        closed = true;
        zookeeper.unregisterRefresh(reconnectRefresh);
        invoker.replaceEndpoints(emptyEndpoints());
        endpointsByKey = Map.of();
    }

    private void onWatchedEvent(WatchedEvent event) {
        if (closed) {
            return;
        }
        Watcher.Event.EventType type = event.getType();
        if (type == Watcher.Event.EventType.NodeChildrenChanged
                || type == Watcher.Event.EventType.NodeCreated
                || type == Watcher.Event.EventType.NodeDeleted) {
            requestRefresh();
        }
    }

    private synchronized void refreshSync() {
        if (closed) {
            return;
        }
        try {
            List<String> children = readChildrenAndInstallWatcher();
            Map<String, MinimalDubboInvoker.Endpoint<T>> next = buildEndpoints(children);
            endpointsByKey = next;
            invoker.replaceEndpoints(toArray(next));
            if (config.check() && next.isEmpty()) {
                throw new DubboConsumerException("No Dubbo provider available for "
                        + spec.serviceInterface().getName() + " from " + providerPath);
            }
        } catch (Exception e) {
            if (config.check()) {
                throw new DubboConsumerException("Failed to refresh Dubbo providers for "
                        + spec.serviceInterface().getName() + " from " + providerPath, e);
            }
        }
    }

    private void requestRefresh() {
        try {
            refreshTask.request();
        } catch (RejectedExecutionException rejected) {
            if (!closed) {
                throw rejected;
            }
        }
    }

    private List<String> readChildrenAndInstallWatcher() throws Exception {
        try {
            return zookeeper.getChildren(providerPath, watcher);
        } catch (KeeperException.NoNodeException e) {
            zookeeper.exists(providerPath, watcher);
            return List.of();
        }
    }

    private Map<String, MinimalDubboInvoker.Endpoint<T>> buildEndpoints(List<String> children) {
        Map<String, MinimalDubboInvoker.Endpoint<T>> previous = endpointsByKey;
        Map<String, MinimalDubboInvoker.Endpoint<T>> next = new HashMap<>(Math.max(1, children.size()));
        for (String child : children) {
            URL providerUrl = parseProviderUrl(child);
            if (providerUrl == null || !DubboUrlFactory.accepts(config, spec, providerUrl)) {
                continue;
            }
            URL mergedUrl = DubboUrlFactory.providerUrl(config, spec, providerUrl);
            String cacheKey = mergedUrl.toFullString();
            MinimalDubboInvoker.Endpoint<T> existing = previous.get(cacheKey);
            if (existing != null) {
                next.put(cacheKey, existing);
                continue;
            }
            Invoker<T> providerInvoker = protocol.refer(spec.serviceInterface(), mergedUrl);
            next.put(cacheKey, new MinimalDubboInvoker.Endpoint<>(cacheKey, providerInvoker));
        }
        return next;
    }

    private URL parseProviderUrl(String child) {
        try {
            return URL.valueOf(URL.decode(child), DubboRuntimeModel.module());
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private MinimalDubboInvoker.Endpoint<T>[] toArray(Map<String, MinimalDubboInvoker.Endpoint<T>> endpoints) {
        return endpoints.values().toArray(new MinimalDubboInvoker.Endpoint[0]);
    }

    @SuppressWarnings("unchecked")
    private MinimalDubboInvoker.Endpoint<T>[] emptyEndpoints() {
        return new MinimalDubboInvoker.Endpoint[0];
    }
}
