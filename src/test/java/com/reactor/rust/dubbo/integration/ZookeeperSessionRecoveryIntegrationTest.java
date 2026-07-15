package com.reactor.rust.dubbo.integration;

import com.reactor.rust.dubbo.DubboConsumerConfig;
import com.reactor.rust.dubbo.internal.registry.ZookeeperRegistryClient;
import com.reactor.rust.dubbo.provider.ZookeeperDubboProviderRegistration;
import org.apache.dubbo.common.URL;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.Duration;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "reactor.zookeeper.integration.container", matches = ".+")
class ZookeeperSessionRecoveryIntegrationTest {

    private static final int SESSION_TIMEOUT_MS = 4_000;

    @Test
    void providerAndConsumerRecoverAfterRealSessionExpiry() throws Exception {
        String container = System.getProperty("reactor.zookeeper.integration.container");
        String address = System.getProperty("reactor.zookeeper.integration.address", "127.0.0.1:2181");
        String root = "dubbo-recovery-" + ProcessHandle.current().pid();
        String serviceName = RecoveryService.class.getName();
        URL providerUrl = new URL(
                "dubbo",
                "127.0.0.1",
                20880,
                serviceName,
                Map.of("application", "recovery-test", "interface", serviceName));
        String nodePath = "/" + root + "/" + URL.encode(serviceName)
                + "/providers/" + URL.encode(providerUrl.toFullString());

        ScheduledExecutorService consumerScheduler = Executors.newSingleThreadScheduledExecutor();
        ZookeeperRegistryClient consumer = new ZookeeperRegistryClient(
                consumerConfig(address, root),
                consumerScheduler);
        AtomicInteger refreshes = new AtomicInteger();
        consumer.registerRefresh(refreshes::incrementAndGet);

        ZookeeperDubboProviderRegistration provider = null;
        boolean containerStopped = false;
        try {
            provider = ZookeeperDubboProviderRegistration.open(providerConfig(address, root));
            provider.register(RecoveryService.class, providerUrl);
            consumer.start();
            waitUntil(Duration.ofSeconds(10), () -> refreshes.get() >= 1);
            assertNodeState(address, nodePath, true);
            long providerGeneration = provider.generation();
            long consumerGeneration = consumer.generation();

            injectSessionExpiration(provider);
            injectSessionExpiration(consumer);
            ZookeeperDubboProviderRegistration activeProvider = provider;
            waitUntil(Duration.ofSeconds(30), () ->
                    activeProvider.isConnected()
                            && activeProvider.generation() > providerGeneration
                            && consumer.isConnected()
                            && consumer.generation() > consumerGeneration
                            && refreshes.get() >= 2);
            assertNodeState(address, nodePath, true);

            int refreshesBeforeRestart = refreshes.get();

            docker("stop", "--time", "0", container);
            containerStopped = true;
            Thread.sleep(SESSION_TIMEOUT_MS + 2_500L);
            docker("start", container);
            containerStopped = false;

            waitUntil(Duration.ofSeconds(30), () ->
                    activeProvider.isConnected()
                            && consumer.isConnected()
                            && refreshes.get() > refreshesBeforeRestart);
            assertNodeState(address, nodePath, true);
        } finally {
            if (containerStopped) {
                docker("start", container);
            }
            if (provider != null) {
                provider.close();
            }
            consumer.close();
            consumerScheduler.shutdownNow();
        }

        waitUntil(Duration.ofSeconds(10), () -> nodeExists(address, nodePath) == false);
    }

    private static DubboConsumerConfig consumerConfig(String address, String root) {
        Properties properties = new Properties();
        properties.setProperty("reactor.dubbo.registry-address", "zookeeper://" + address);
        properties.setProperty("reactor.dubbo.registry-root", root);
        properties.setProperty("reactor.dubbo.registry-timeout-ms", "10000");
        properties.setProperty("reactor.dubbo.registry-session-timeout-ms", Integer.toString(SESSION_TIMEOUT_MS));
        properties.setProperty("reactor.dubbo.registry-reconnect-initial-delay-ms", "100");
        properties.setProperty("reactor.dubbo.registry-reconnect-max-delay-ms", "1000");
        return DubboConsumerConfig.fromProperties(properties);
    }

    private static ZookeeperDubboProviderRegistration.RegistryConfig providerConfig(
            String address,
            String root) {
        return new ZookeeperDubboProviderRegistration.RegistryConfig(
                "zookeeper://" + address,
                root,
                "recovery-test",
                10_000,
                SESSION_TIMEOUT_MS,
                100,
                1_000,
                "",
                "",
                ZookeeperDubboProviderRegistration.AclMode.OPEN);
    }

    private static void assertNodeState(String address, String path, boolean expected) throws Exception {
        waitUntil(Duration.ofSeconds(10), () -> nodeExists(address, path) == expected);
    }

    private static boolean nodeExists(String address, String path) {
        CountDownLatch connected = new CountDownLatch(1);
        ZooKeeper probe = null;
        try {
            probe = new ZooKeeper(address, SESSION_TIMEOUT_MS, event -> {
                if (event.getState() == Watcher.Event.KeeperState.SyncConnected) {
                    connected.countDown();
                }
            });
            if (!connected.await(3, TimeUnit.SECONDS)) {
                return false;
            }
            return probe.exists(path, false) != null;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (probe != null) {
                try {
                    probe.close();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private static void waitUntil(Duration timeout, BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100L);
        }
        assertTrue(condition.getAsBoolean(), "Condition did not become true within " + timeout);
    }

    private static void injectSessionExpiration(Object registryClient) throws Exception {
        Field field = registryClient.getClass().getDeclaredField("zookeeper");
        field.setAccessible(true);
        ZooKeeper zookeeper = (ZooKeeper) field.get(registryClient);
        zookeeper.getTestable().injectSessionExpiration();
    }

    private static void docker(String... arguments) throws Exception {
        String[] command = new String[arguments.length + 1];
        command[0] = "docker";
        System.arraycopy(arguments, 0, command, 1, arguments.length);
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes());
        if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IllegalStateException("Docker command failed: " + String.join(" ", command)
                    + " output=" + output);
        }
    }

    private interface RecoveryService {
        byte[] read();
    }
}
