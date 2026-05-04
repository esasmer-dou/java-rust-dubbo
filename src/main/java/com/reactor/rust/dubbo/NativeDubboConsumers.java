package com.reactor.rust.dubbo;

import java.util.Properties;

public final class NativeDubboConsumers {

    private NativeDubboConsumers() {}

    public static NativeDubboConsumerClient create() {
        return NativeDubboConsumerClient.create(DubboConsumerConfig.fromProperties());
    }

    public static NativeDubboConsumerClient create(Properties properties) {
        return NativeDubboConsumerClient.create(DubboConsumerConfig.fromProperties(properties));
    }

    public static NativeDubboConsumerClient create(DubboConsumerConfig config) {
        return NativeDubboConsumerClient.create(config);
    }

    public static <T> T reference(NativeDubboConsumerClient client, Class<T> serviceInterface) {
        return client.get(serviceInterface);
    }

    public static <T, R> NativeDubboMethodInvoker<R> method(
            NativeDubboConsumerClient client,
            DubboReferenceSpec<T> spec,
            String methodName,
            Class<R> returnType,
            Class<?>... parameterTypes) {
        return client.method(spec, methodName, returnType, parameterTypes);
    }
}
