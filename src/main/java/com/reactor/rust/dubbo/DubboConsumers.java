package com.reactor.rust.dubbo;

import java.util.Properties;

public final class DubboConsumers {

    private DubboConsumers() {}

    public static DubboConsumerClient create() {
        return DubboConsumerClient.create(DubboConsumerConfig.fromProperties());
    }

    public static DubboConsumerClient create(Properties properties) {
        return DubboConsumerClient.create(DubboConsumerConfig.fromProperties(properties));
    }

    public static DubboConsumerClient create(DubboConsumerConfig config) {
        return DubboConsumerClient.create(config);
    }

    public static <T> T reference(DubboConsumerClient client, Class<T> serviceInterface) {
        return client.get(serviceInterface);
    }

    public static <T> T reference(DubboConsumerClient client, DubboReferenceSpec<T> spec) {
        return client.get(spec);
    }

    public static <T, R> DubboMethodInvoker<R> method(
            DubboConsumerClient client,
            DubboReferenceSpec<T> spec,
            String methodName,
            Class<R> returnType,
            Class<?>... parameterTypes) {
        return client.method(spec, methodName, returnType, parameterTypes);
    }
}
