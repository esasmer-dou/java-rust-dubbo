package com.reactor.rust.dubbo.provider;

import org.apache.dubbo.common.URL;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class ZookeeperDubboProviderRegistration implements DubboProviderRegistration {

    private final ZooKeeper zookeeper;
    private final String registryRoot;
    private final String nodePayload;
    private final List<String> nodePaths = new ArrayList<>();

    private ZookeeperDubboProviderRegistration(ZooKeeper zookeeper, String registryRoot, String nodePayload) {
        this.zookeeper = zookeeper;
        this.registryRoot = registryRoot;
        this.nodePayload = nodePayload;
    }

    public static ZookeeperDubboProviderRegistration open(
            String registryAddress,
            String registryRoot,
            String nodePayload) throws Exception {
        CountDownLatch connected = new CountDownLatch(1);
        ZooKeeper zk = new ZooKeeper(
                zookeeperConnectString(registryAddress),
                30_000,
                event -> onEvent(event, connected)
        );
        if (!connected.await(5, TimeUnit.SECONDS)) {
            zk.close();
            throw new IllegalStateException("Timed out connecting to Zookeeper: " + registryAddress);
        }

        String root = normalizeRoot(registryRoot);
        ensurePersistent(zk, "/" + root);
        return new ZookeeperDubboProviderRegistration(zk, root, normalizePayload(nodePayload));
    }

    public static ZookeeperDubboProviderRegistration open(
            String registryAddress,
            String registryRoot) throws Exception {
        return open(registryAddress, registryRoot, "java-rust-dubbo-provider");
    }

    @Override
    public synchronized void register(Class<?> serviceType, URL providerUrl) throws Exception {
        String servicePath = "/" + registryRoot + "/" + URL.encode(serviceType.getName());
        String providersPath = servicePath + "/providers";
        ensurePersistent(zookeeper, servicePath);
        ensurePersistent(zookeeper, providersPath);

        String nodePath = providersPath + "/" + URL.encode(providerUrl.toFullString());
        recreateEphemeral(zookeeper, nodePath, nodePayload);
        nodePaths.add(nodePath);
    }

    @Override
    public synchronized void close() {
        try {
            for (int i = nodePaths.size() - 1; i >= 0; i--) {
                String nodePath = nodePaths.get(i);
                try {
                    if (zookeeper.exists(nodePath, false) != null) {
                        zookeeper.delete(nodePath, -1);
                    }
                } catch (Exception ignored) {
                    // Session close also removes ephemeral nodes.
                }
            }
        } finally {
            try {
                zookeeper.close();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void onEvent(WatchedEvent event, CountDownLatch connected) {
        if (event.getState() == Watcher.Event.KeeperState.SyncConnected
                || event.getState() == Watcher.Event.KeeperState.ConnectedReadOnly) {
            connected.countDown();
        }
    }

    private static void ensurePersistent(ZooKeeper zk, String path) throws Exception {
        if (zk.exists(path, false) != null) {
            return;
        }
        try {
            zk.create(path, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        } catch (KeeperException.NodeExistsException ignored) {
            // Concurrent providers can create the same parent path.
        }
    }

    private static void recreateEphemeral(ZooKeeper zk, String path, String nodePayload) throws Exception {
        try {
            if (zk.exists(path, false) != null) {
                zk.delete(path, -1);
            }
        } catch (KeeperException.NoNodeException ignored) {
            // No existing node.
        }
        zk.create(
                path,
                nodePayload.getBytes(StandardCharsets.UTF_8),
                ZooDefs.Ids.OPEN_ACL_UNSAFE,
                CreateMode.EPHEMERAL
        );
    }

    private static String zookeeperConnectString(String address) {
        String trimmed = address == null ? "" : address.trim();
        int scheme = trimmed.indexOf("://");
        if (scheme >= 0) {
            trimmed = trimmed.substring(scheme + 3);
        }
        int query = trimmed.indexOf('?');
        if (query >= 0) {
            trimmed = trimmed.substring(0, query);
        }
        int slash = trimmed.indexOf('/');
        if (slash >= 0) {
            trimmed = trimmed.substring(0, slash);
        }
        if (trimmed.isBlank()) {
            throw new IllegalArgumentException("registry address must not be blank");
        }
        return trimmed;
    }

    private static String normalizeRoot(String root) {
        String value = root == null || root.isBlank() ? "dubbo" : root.trim();
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value.isBlank() ? "dubbo" : value;
    }

    private static String normalizePayload(String nodePayload) {
        return nodePayload == null || nodePayload.isBlank() ? "java-rust-dubbo-provider" : nodePayload.trim();
    }
}
