package com.reactor.rust.dubbo;

final class RegistryAddress {

    private RegistryAddress() {}

    static String zookeeperConnectString(String registryAddress) {
        String value = DubboConsumerConfig.normalizeRegistryAddress(registryAddress);
        int scheme = value.indexOf("://");
        if (scheme >= 0) {
            value = value.substring(scheme + 3);
        }
        int query = value.indexOf('?');
        if (query >= 0) {
            value = value.substring(0, query);
        }
        int slash = value.indexOf('/');
        if (slash >= 0) {
            value = value.substring(0, slash);
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException("Zookeeper connect string is empty: " + registryAddress);
        }
        return value;
    }
}
