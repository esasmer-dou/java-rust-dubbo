package com.reactor.rust.dubbo.internal.registry;

import com.reactor.rust.dubbo.DubboConsumerConfig;
import com.reactor.rust.dubbo.DubboReferenceSpec;
import com.reactor.rust.dubbo.internal.runtime.DubboRuntimeModel;

import org.apache.dubbo.common.URL;

import java.util.HashMap;
import java.util.Map;

public final class DubboUrlFactory {

    private static final String APPLICATION_KEY = "application";
    private static final String CATEGORY_KEY = "category";
    private static final String CHECK_KEY = "check";
    private static final String CLIENT_KEY = "client";
    private static final String CLUSTER_KEY = "cluster";
    private static final String CONNECTIONS_KEY = "connections";
    private static final String CONSUMER_SIDE = "consumer";
    private static final String GROUP_KEY = "group";
    private static final String INTERFACE_KEY = "interface";
    private static final String LAZY_CONNECT_KEY = "lazy";
    private static final String LOADBALANCE_KEY = "loadbalance";
    private static final String PROVIDERS_CATEGORY = "providers";
    private static final String PROTOCOL_KEY = "protocol";
    private static final String RETRIES_KEY = "retries";
    private static final String SERIALIZATION_KEY = "serialization";
    private static final String SHARE_CONNECTIONS_KEY = "shareconnections";
    private static final String SIDE_KEY = "side";
    private static final String TIMEOUT_KEY = "timeout";
    private static final String VERSION_KEY = "version";
    private static final String NETTY4_CLIENT = "netty4";

    private DubboUrlFactory() {}

    public static <T> URL consumerUrl(DubboConsumerConfig config, DubboReferenceSpec<T> spec) {
        Map<String, String> parameters = consumerParameters(config, spec);
        return scoped(new URL(CONSUMER_SIDE, "0.0.0.0", 0, spec.serviceInterface().getName(), parameters));
    }

    public static <T> URL providerUrl(DubboConsumerConfig config, DubboReferenceSpec<T> spec, URL providerUrl) {
        Map<String, String> merged = new HashMap<>(providerUrl.getParameters());
        merged.putAll(consumerParameters(config, spec));
        merged.put(CHECK_KEY, "false");
        URL url = providerUrl.clearParameters().addParameters(merged);
        if (url.getPath() == null || url.getPath().isBlank()) {
            url = new URL(url.getProtocol(), url.getUsername(), url.getPassword(), url.getHost(), url.getPort(),
                    spec.serviceInterface().getName(), url.getParameters());
        }
        return scoped(url);
    }

    public static <T> String providerPath(DubboConsumerConfig config, DubboReferenceSpec<T> spec) {
        return "/" + config.registryRoot() + "/" + URL.encode(spec.serviceInterface().getName()) + "/"
                + PROVIDERS_CATEGORY;
    }

    public static <T> boolean accepts(DubboConsumerConfig config, DubboReferenceSpec<T> spec, URL providerUrl) {
        String protocol = valueOrDefault(spec.protocol(), config.protocol());
        if (!protocol.equals(providerUrl.getProtocol())) {
            return false;
        }
        if (!isEnabled(providerUrl)) {
            return false;
        }
        String group = spec.group();
        if (group != null && !group.equals(providerUrl.getGroup())) {
            return false;
        }
        String version = spec.version();
        return version == null || version.equals(providerUrl.getVersion());
    }

    private static <T> Map<String, String> consumerParameters(DubboConsumerConfig config, DubboReferenceSpec<T> spec) {
        Map<String, String> parameters = new HashMap<>(18);
        parameters.put(APPLICATION_KEY, config.applicationName());
        parameters.put(INTERFACE_KEY, spec.serviceInterface().getName());
        parameters.put(PROTOCOL_KEY, valueOrDefault(spec.protocol(), config.protocol()));
        parameters.put(SERIALIZATION_KEY, valueOrDefault(spec.serialization(), config.serialization()));
        parameters.put(CLIENT_KEY, NETTY4_CLIENT);
        parameters.put(TIMEOUT_KEY, Integer.toString(valueOrDefault(spec.timeoutMs(), config.timeoutMs())));
        parameters.put(RETRIES_KEY, Integer.toString(valueOrDefault(spec.retries(), config.retries())));
        parameters.put(CHECK_KEY, Boolean.toString(valueOrDefault(spec.check(), config.check())));
        parameters.put(LAZY_CONNECT_KEY, Boolean.toString(valueOrDefault(spec.lazy(), config.lazy())));
        parameters.put(CONNECTIONS_KEY, Integer.toString(valueOrDefault(spec.connections(), config.connections())));
        parameters.put(SHARE_CONNECTIONS_KEY, Integer.toString(config.shareConnections()));
        parameters.put(CLUSTER_KEY, valueOrDefault(spec.cluster(), config.cluster()));
        parameters.put(LOADBALANCE_KEY, valueOrDefault(spec.loadbalance(), config.loadbalance()));
        parameters.put(SIDE_KEY, CONSUMER_SIDE);
        parameters.put(CATEGORY_KEY, PROVIDERS_CATEGORY);
        if (spec.group() != null) {
            parameters.put(GROUP_KEY, spec.group());
        }
        if (spec.version() != null) {
            parameters.put(VERSION_KEY, spec.version());
        }
        return parameters;
    }

    private static URL scoped(URL url) {
        return url.setScopeModel(DubboRuntimeModel.module());
    }

    private static boolean isEnabled(URL url) {
        if (url.hasParameter("disabled")) {
            return !url.getParameter("disabled", false);
        }
        return url.getParameter("enabled", true);
    }

    private static int valueOrDefault(Integer value, int defaultValue) {
        return value == null ? defaultValue : value;
    }

    private static boolean valueOrDefault(Boolean value, boolean defaultValue) {
        return value == null ? defaultValue : value;
    }

    private static String valueOrDefault(String value, String defaultValue) {
        return value == null ? defaultValue : value;
    }
}
