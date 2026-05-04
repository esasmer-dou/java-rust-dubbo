package com.reactor.rust.dubbo;

import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

final class NativeDubboProviderWatcher<T> implements ProviderWatcher {

    private static final String PROVIDERS_CATEGORY = "providers";

    private final DubboConsumerConfig config;
    private final DubboReferenceSpec<T> spec;
    private final ZookeeperRegistryClient zookeeper;
    private final Executor refreshExecutor;
    private final int nativeClientId;
    private final String providerPath;
    private final Watcher watcher = this::onWatchedEvent;
    private final Runnable reconnectRefresh = this::refreshSync;
    private volatile boolean closed;

    NativeDubboProviderWatcher(
            DubboConsumerConfig config,
            DubboReferenceSpec<T> spec,
            ZookeeperRegistryClient zookeeper,
            Executor refreshExecutor,
            int nativeClientId) {
        this.config = config;
        this.spec = spec;
        this.zookeeper = zookeeper;
        this.refreshExecutor = refreshExecutor;
        this.nativeClientId = nativeClientId;
        this.providerPath = providerPath(config, spec);
    }

    public void start() {
        zookeeper.registerRefresh(reconnectRefresh);
        refreshSync();
    }

    @Override
    public void close() {
        closed = true;
        zookeeper.unregisterRefresh(reconnectRefresh);
        NativeDubboBridge.updateProviders(nativeClientId, "");
    }

    private void onWatchedEvent(WatchedEvent event) {
        if (closed) {
            return;
        }
        Watcher.Event.EventType type = event.getType();
        if (type == Watcher.Event.EventType.NodeChildrenChanged
                || type == Watcher.Event.EventType.NodeCreated
                || type == Watcher.Event.EventType.NodeDeleted) {
            refreshExecutor.execute(this::refreshSync);
        }
    }

    private synchronized void refreshSync() {
        if (closed) {
            return;
        }
        try {
            List<String> children = readChildrenAndInstallWatcher();
            List<String> endpoints = buildEndpoints(children);
            if (config.check() && endpoints.isEmpty()) {
                throw new DubboConsumerException("No native Dubbo provider available for "
                        + spec.serviceInterface().getName() + " from " + providerPath);
            }
            NativeDubboBridge.updateProviders(nativeClientId, String.join(",", endpoints));
        } catch (Exception e) {
            if (config.check()) {
                throw new DubboConsumerException("Failed to refresh native Dubbo providers for "
                        + spec.serviceInterface().getName() + " from " + providerPath, e);
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

    private List<String> buildEndpoints(List<String> children) {
        List<String> endpoints = new ArrayList<>(children.size());
        for (String child : children) {
            ProviderNode node = ProviderNode.parse(child);
            if (node == null || !accepts(node)) {
                continue;
            }
            endpoints.add(node.host + ":" + node.port);
        }
        return endpoints;
    }

    private boolean accepts(ProviderNode node) {
        String protocol = valueOrDefault(spec.protocol(), config.protocol());
        if (!protocol.equals(node.protocol)) {
            return false;
        }
        if (!node.enabled()) {
            return false;
        }
        String group = spec.group();
        if (group != null && !group.equals(node.parameters.get("group"))) {
            return false;
        }
        String version = spec.version();
        return version == null || version.equals(node.parameters.get("version"));
    }

    private static <T> String providerPath(DubboConsumerConfig config, DubboReferenceSpec<T> spec) {
        String encodedInterface = URLEncoder.encode(spec.serviceInterface().getName(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        return "/" + config.registryRoot() + "/" + encodedInterface + "/" + PROVIDERS_CATEGORY;
    }

    private static String valueOrDefault(String value, String defaultValue) {
        return value == null ? defaultValue : value;
    }

    private record ProviderNode(String protocol, String host, int port, Map<String, String> parameters) {
        static ProviderNode parse(String child) {
            try {
                String decoded = URLDecoder.decode(child, StandardCharsets.UTF_8);
                int scheme = decoded.indexOf("://");
                if (scheme <= 0) {
                    return null;
                }
                String protocol = decoded.substring(0, scheme);
                int authorityStart = scheme + 3;
                int pathStart = decoded.indexOf('/', authorityStart);
                int queryStart = decoded.indexOf('?', authorityStart);
                int authorityEnd;
                if (pathStart < 0) {
                    authorityEnd = queryStart < 0 ? decoded.length() : queryStart;
                } else {
                    authorityEnd = queryStart < 0 ? pathStart : Math.min(pathStart, queryStart);
                }
                String authority = decoded.substring(authorityStart, authorityEnd);
                int userInfo = authority.lastIndexOf('@');
                if (userInfo >= 0) {
                    authority = authority.substring(userInfo + 1);
                }
                HostPort hostPort = parseHostPort(authority);
                return new ProviderNode(protocol, hostPort.host, hostPort.port, query(decoded, queryStart));
            } catch (RuntimeException ignored) {
                return null;
            }
        }

        boolean enabled() {
            if (Boolean.parseBoolean(parameters.getOrDefault("disabled", "false"))) {
                return false;
            }
            return Boolean.parseBoolean(parameters.getOrDefault("enabled", "true"));
        }

        private static HostPort parseHostPort(String authority) {
            if (authority.startsWith("[")) {
                int end = authority.indexOf(']');
                if (end <= 1 || end + 2 > authority.length() || authority.charAt(end + 1) != ':') {
                    throw new IllegalArgumentException("Invalid IPv6 Dubbo authority: " + authority);
                }
                return new HostPort(authority.substring(1, end), Integer.parseInt(authority.substring(end + 2)));
            }
            int colon = authority.lastIndexOf(':');
            if (colon <= 0 || colon == authority.length() - 1) {
                throw new IllegalArgumentException("Invalid Dubbo authority: " + authority);
            }
            return new HostPort(authority.substring(0, colon), Integer.parseInt(authority.substring(colon + 1)));
        }

        private static Map<String, String> query(String decoded, int queryStart) {
            if (queryStart < 0 || queryStart == decoded.length() - 1) {
                return Map.of();
            }
            Map<String, String> parameters = new HashMap<>();
            String query = decoded.substring(queryStart + 1);
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
                String value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                parameters.put(key, value);
            }
            return parameters;
        }
    }

    private record HostPort(String host, int port) {}
}
