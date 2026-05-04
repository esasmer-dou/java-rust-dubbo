package com.reactor.rust.dubbo;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;

import java.util.Arrays;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

final class MinimalDubboInvoker<T> implements Invoker<T> {

    private static final Endpoint<?>[] EMPTY_ENDPOINTS = new Endpoint<?>[0];

    private final Class<T> serviceInterface;
    private final URL consumerUrl;
    private final String cluster;
    private final String loadbalance;
    private final int retries;
    private final boolean failfastNoRetry;
    private final Semaphore inflight;
    private final AtomicInteger roundRobin = new AtomicInteger();
    private volatile Endpoint<T>[] endpoints = emptyEndpoints();
    private volatile boolean destroyed;

    MinimalDubboInvoker(DubboConsumerConfig config, DubboReferenceSpec<T> spec) {
        this.serviceInterface = spec.serviceInterface();
        this.consumerUrl = DubboUrlFactory.consumerUrl(config, spec);
        this.cluster = valueOrDefault(spec.cluster(), config.cluster());
        this.loadbalance = valueOrDefault(spec.loadbalance(), config.loadbalance());
        this.retries = valueOrDefault(spec.retries(), config.retries());
        this.failfastNoRetry = "failfast".equals(cluster) && retries == 0;
        this.inflight = config.maxInflight() == 0 ? null : new Semaphore(config.maxInflight());
    }

    @Override
    public Class<T> getInterface() {
        return serviceInterface;
    }

    @Override
    public URL getUrl() {
        return consumerUrl;
    }

    @Override
    public boolean isAvailable() {
        if (destroyed) {
            return false;
        }
        for (Endpoint<T> endpoint : endpoints) {
            if (endpoint.invoker().isAvailable()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Result invoke(Invocation invocation) throws RpcException {
        if (inflight != null && !inflight.tryAcquire()) {
            throw new RpcException("Dubbo max in-flight exceeded for " + serviceInterface.getName());
        }
        boolean releaseOnExit = true;
        try {
            Result result = doInvoke(invocation);
            if (inflight == null) {
                return result;
            }
            releaseOnExit = false;
            return result.whenCompleteWithContext((ignored, error) -> inflight.release());
        } finally {
            if (releaseOnExit && inflight != null) {
                inflight.release();
            }
        }
    }

    private Result doInvoke(Invocation invocation) throws RpcException {
        if (destroyed) {
            throw new RpcException("Dubbo reference is destroyed: " + serviceInterface.getName());
        }
        Endpoint<T>[] snapshot = endpoints;
        if (snapshot.length == 0) {
            throw new RpcException("No Dubbo provider available for " + serviceInterface.getName());
        }
        if (snapshot.length == 1 && failfastNoRetry) {
            return snapshot[0].invoker().invoke(invocation);
        }

        int attempts = "failover".equals(cluster) ? Math.min(retries + 1, snapshot.length) : 1;
        int start = selectStart(snapshot.length);
        RpcException last = null;
        for (int i = 0; i < attempts; i++) {
            Endpoint<T> endpoint = snapshot[(start + i) % snapshot.length];
            if (!endpoint.invoker().isAvailable() && attempts > 1) {
                continue;
            }
            try {
                return endpoint.invoker().invoke(invocation);
            } catch (RpcException e) {
                last = e;
                if (!"failover".equals(cluster)) {
                    throw e;
                }
            }
        }
        if (last != null) {
            throw last;
        }
        throw new RpcException("No available Dubbo provider for " + serviceInterface.getName());
    }

    @Override
    public void destroy() {
        if (destroyed) {
            return;
        }
        destroyed = true;
        replaceEndpoints(emptyEndpoints());
    }

    void replaceEndpoints(Endpoint<T>[] next) {
        Endpoint<T>[] previous = endpoints;
        endpoints = next == null ? emptyEndpoints() : next;
        for (Endpoint<T> endpoint : previous) {
            if (!contains(endpoints, endpoint.cacheKey())) {
                endpoint.invoker().destroy();
            }
        }
    }

    private int selectStart(int length) {
        if ("roundrobin".equals(loadbalance)) {
            return Math.floorMod(roundRobin.getAndIncrement(), length);
        }
        return ThreadLocalRandom.current().nextInt(length);
    }

    private static boolean contains(Endpoint<?>[] endpoints, String cacheKey) {
        return Arrays.stream(endpoints).anyMatch(endpoint -> endpoint.cacheKey().equals(cacheKey));
    }

    @SuppressWarnings("unchecked")
    private static <T> Endpoint<T>[] emptyEndpoints() {
        return (Endpoint<T>[]) EMPTY_ENDPOINTS;
    }

    private static int valueOrDefault(Integer value, int defaultValue) {
        return value == null ? defaultValue : value;
    }

    private static String valueOrDefault(String value, String defaultValue) {
        return value == null ? defaultValue : value;
    }

    record Endpoint<T>(String cacheKey, Invoker<T> invoker) {}
}
