package com.reactor.rust.dubbo.provider;

import com.reactor.rust.dubbo.internal.util.NamedDaemonThreadFactory;

import org.apache.dubbo.common.URL;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Stat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class ZookeeperDubboProviderRegistration implements DubboProviderRegistration {

    private static final System.Logger LOG = System.getLogger(ZookeeperDubboProviderRegistration.class.getName());

    private final RegistryConfig config;
    private final ScheduledThreadPoolExecutor scheduler;
    private final Map<String, Registration> registrations = new LinkedHashMap<>();
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean();
    private final AtomicBoolean registrationRefreshScheduled = new AtomicBoolean();
    private final AtomicInteger reconnectAttempt = new AtomicInteger();
    private final AtomicInteger registrationRefreshAttempt = new AtomicInteger();
    private final AtomicLong generation = new AtomicLong();

    private volatile ZooKeeper zookeeper;
    private volatile CountDownLatch connectedLatch = new CountDownLatch(1);
    private volatile boolean connected;
    private volatile boolean closed;
    private volatile IllegalStateException terminalFailure;
    private volatile String lastFailure = "";

    private ZookeeperDubboProviderRegistration(RegistryConfig config) {
        this.config = config;
        this.scheduler = new ScheduledThreadPoolExecutor(
                1,
                new NamedDaemonThreadFactory("reactor-dubbo-provider-zk"));
        this.scheduler.setKeepAliveTime(30L, TimeUnit.SECONDS);
        this.scheduler.allowCoreThreadTimeOut(true);
        this.scheduler.setRemoveOnCancelPolicy(true);
        this.scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        this.scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
    }

    public static ZookeeperDubboProviderRegistration open(RegistryConfig config) throws Exception {
        ZookeeperDubboProviderRegistration registration =
                new ZookeeperDubboProviderRegistration(config);
        boolean started = false;
        try {
            registration.start();
            started = true;
            return registration;
        } finally {
            if (!started) {
                registration.close();
            }
        }
    }

    public static ZookeeperDubboProviderRegistration open(
            String registryAddress,
            String registryRoot,
            String nodePayload) throws Exception {
        return open(RegistryConfig.defaults(registryAddress, registryRoot, nodePayload));
    }

    public static ZookeeperDubboProviderRegistration open(
            String registryAddress,
            String registryRoot) throws Exception {
        return open(registryAddress, registryRoot, "java-rust-dubbo-provider");
    }

    @Override
    public void register(Class<?> serviceType, URL providerUrl) throws Exception {
        String servicePath = "/" + config.registryRoot() + "/" + URL.encode(serviceType.getName());
        String providersPath = servicePath + "/providers";
        String nodePath = providersPath + "/" + URL.encode(providerUrl.toFullString());
        Registration registration = new Registration(servicePath, providersPath, nodePath);
        synchronized (this) {
            ensureOpen();
            registrations.put(nodePath, registration);
        }
        registerOne(current(), registration);
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
        List<Registration> registered;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            connected = false;
            generation.incrementAndGet();
            current = zookeeper;
            zookeeper = null;
            registered = new ArrayList<>(registrations.values());
            registrations.clear();
        }
        deleteOwnedNodes(current, registered);
        closeQuietly(current);
        scheduler.shutdownNow();
    }

    private void start() throws Exception {
        openNewSession();
        awaitConnected();
        ensurePersistent(current(), "/" + config.registryRoot());
    }

    private ZooKeeper current() {
        IllegalStateException failure = terminalFailure;
        if (failure != null) {
            throw failure;
        }
        ZooKeeper current = zookeeper;
        if (current == null || !connected || !current.getState().isAlive()) {
            throw new IllegalStateException("Zookeeper provider registration is not connected");
        }
        return current;
    }

    private void openNewSession() throws IOException {
        ZooKeeper previous;
        synchronized (this) {
            ensureOpen();
            long nextGeneration = generation.incrementAndGet();
            connected = false;
            connectedLatch = new CountDownLatch(1);
            SessionWatcher watcher = new SessionWatcher(nextGeneration);
            ZooKeeper created = new ZooKeeper(
                    zookeeperConnectString(config.registryAddress()),
                    config.sessionTimeoutMs(),
                    watcher);
            if (config.authenticationEnabled()) {
                created.addAuthInfo(
                        config.authScheme(),
                        config.authData().getBytes(StandardCharsets.UTF_8));
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
        if (state == Watcher.Event.KeeperState.SyncConnected) {
            connected = true;
            lastFailure = "";
            reconnectAttempt.set(0);
            connectedLatch.countDown();
            scheduleRegistrationRefresh(0L);
            return;
        }
        if (state == Watcher.Event.KeeperState.ConnectedReadOnly) {
            // Providers must create/update ephemeral nodes. A read-only ensemble connection is
            // useful for consumers, but it is not a valid provider registration state.
            connected = false;
            lastFailure = "Zookeeper provider connection is read-only";
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
            terminalFailure = new IllegalStateException(
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
                } catch (Exception e) {
                    recordFailure("Zookeeper provider reconnect failed", e);
                    scheduleReconnect(nextDelay(reconnectAttempt));
                }
            }, delayMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            reconnectScheduled.set(false);
            if (!closed) {
                throw e;
            }
        }
    }

    private void scheduleRegistrationRefresh(long delayMs) {
        if (closed || terminalFailure != null
                || !registrationRefreshScheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            scheduler.schedule(() -> {
                registrationRefreshScheduled.set(false);
                if (closed || terminalFailure != null || !connected) {
                    return;
                }
                try {
                    registerAll();
                    registrationRefreshAttempt.set(0);
                } catch (Exception e) {
                    recordFailure("Zookeeper provider re-registration failed", e);
                    scheduleRegistrationRefresh(nextDelay(registrationRefreshAttempt));
                }
            }, delayMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            registrationRefreshScheduled.set(false);
            if (!closed) {
                throw e;
            }
        }
    }

    private void registerAll() throws Exception {
        List<Registration> snapshot;
        synchronized (this) {
            snapshot = new ArrayList<>(registrations.values());
        }
        ZooKeeper current = current();
        ensurePersistent(current, "/" + config.registryRoot());
        for (Registration registration : snapshot) {
            registerOne(current, registration);
        }
    }

    private void registerOne(ZooKeeper current, Registration registration) throws Exception {
        ensurePersistent(current, registration.servicePath());
        ensurePersistent(current, registration.providersPath());
        createOrUpdateOwnedEphemeral(current, registration.nodePath());
    }

    private void ensurePersistent(ZooKeeper current, String path) throws Exception {
        if (current.exists(path, false) != null) {
            return;
        }
        try {
            current.create(path, new byte[0], config.acl(), CreateMode.PERSISTENT);
        } catch (KeeperException.NodeExistsException ignored) {
            // Concurrent providers may create the same parent path.
        }
    }

    private void createOrUpdateOwnedEphemeral(ZooKeeper current, String path) throws Exception {
        byte[] payload = config.nodePayload().getBytes(StandardCharsets.UTF_8);
        try {
            current.create(path, payload, config.acl(), CreateMode.EPHEMERAL);
        } catch (KeeperException.NodeExistsException e) {
            Stat stat = current.exists(path, false);
            if (stat != null && stat.getEphemeralOwner() == current.getSessionId()) {
                current.setData(path, payload, stat.getVersion());
                return;
            }
            throw new IllegalStateException(
                    "Provider node is owned by another live Zookeeper session: " + path,
                    e);
        }
    }

    private void awaitConnected() throws Exception {
        CountDownLatch latch = connectedLatch;
        boolean signalled = latch.await(config.connectTimeoutMs(), TimeUnit.MILLISECONDS);
        IllegalStateException failure = terminalFailure;
        if (failure != null) {
            throw failure;
        }
        if (!signalled || !connected) {
            throw new IllegalStateException("Timed out connecting to Zookeeper: " + config.registryAddress());
        }
    }

    private long nextDelay(AtomicInteger attemptCounter) {
        int attempt = Math.min(attemptCounter.getAndIncrement(), 20);
        long initial = config.reconnectInitialDelayMs();
        long multiplier = 1L << attempt;
        long candidate = initial > Long.MAX_VALUE / multiplier
                ? Long.MAX_VALUE
                : initial * multiplier;
        return Math.min(candidate, config.reconnectMaxDelayMs());
    }

    private void recordFailure(String operation, Exception failure) {
        lastFailure = failure.getMessage() == null ? failure.getClass().getName() : failure.getMessage();
        LOG.log(System.Logger.Level.WARNING, operation + ": {0}", lastFailure);
    }

    private synchronized void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Zookeeper provider registration is closed");
        }
    }

    private static void deleteOwnedNodes(ZooKeeper current, List<Registration> registrations) {
        if (current == null) {
            return;
        }
        long sessionId = current.getSessionId();
        for (int i = registrations.size() - 1; i >= 0; i--) {
            String nodePath = registrations.get(i).nodePath();
            try {
                Stat stat = current.exists(nodePath, false);
                if (stat != null && stat.getEphemeralOwner() == sessionId) {
                    current.delete(nodePath, stat.getVersion());
                }
            } catch (Exception ignored) {
                // Session close also removes owned ephemeral nodes.
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
        return nodePayload == null || nodePayload.isBlank()
                ? "java-rust-dubbo-provider"
                : nodePayload.trim();
    }

    private record Registration(String servicePath, String providersPath, String nodePath) {
    }

    public enum AclMode {
        AUTO,
        OPEN,
        CREATOR;

        public static AclMode parse(String value) {
            if (value == null || value.isBlank()) {
                return AUTO;
            }
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("registry ACL must be auto, open, or creator: " + value, e);
            }
        }
    }

    public record RegistryConfig(
            String registryAddress,
            String registryRoot,
            String nodePayload,
            int connectTimeoutMs,
            int sessionTimeoutMs,
            int reconnectInitialDelayMs,
            int reconnectMaxDelayMs,
            String authScheme,
            String authData,
            AclMode aclMode) {

        public RegistryConfig {
            if (registryAddress == null || registryAddress.isBlank()) {
                throw new IllegalArgumentException("registryAddress must not be blank");
            }
            registryAddress = registryAddress.trim();
            registryRoot = normalizeRoot(registryRoot);
            nodePayload = normalizePayload(nodePayload);
            if (connectTimeoutMs < 1 || sessionTimeoutMs < 1
                    || reconnectInitialDelayMs < 1 || reconnectMaxDelayMs < reconnectInitialDelayMs) {
                throw new IllegalArgumentException("Registry timeouts and reconnect delays are invalid");
            }
            authScheme = authScheme == null ? "" : authScheme.trim();
            authData = authData == null ? "" : authData.trim();
            if (authScheme.isEmpty() != authData.isEmpty()) {
                throw new IllegalArgumentException("authScheme and authData must both be set or both be blank");
            }
            aclMode = aclMode == null ? AclMode.AUTO : aclMode;
            if (aclMode == AclMode.CREATOR && authScheme.isEmpty()) {
                throw new IllegalArgumentException("creator ACL requires registry authentication");
            }
        }

        public static RegistryConfig defaults(String address, String root, String payload) {
            return new RegistryConfig(
                    address,
                    root,
                    payload,
                    5_000,
                    30_000,
                    250,
                    10_000,
                    "",
                    "",
                    AclMode.AUTO);
        }

        public boolean authenticationEnabled() {
            return !authScheme.isEmpty();
        }

        public List<ACL> acl() {
            AclMode effective = aclMode == AclMode.AUTO
                    ? (authenticationEnabled() ? AclMode.CREATOR : AclMode.OPEN)
                    : aclMode;
            return effective == AclMode.CREATOR
                    ? ZooDefs.Ids.CREATOR_ALL_ACL
                    : ZooDefs.Ids.OPEN_ACL_UNSAFE;
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
