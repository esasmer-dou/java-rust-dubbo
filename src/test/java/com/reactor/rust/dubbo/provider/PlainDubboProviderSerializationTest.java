package com.reactor.rust.dubbo.provider;

import com.reactor.rust.dubbo.internal.runtime.DubboRuntimeModel;
import org.apache.dubbo.common.utils.DefaultSerializeClassChecker;
import org.junit.jupiter.api.Test;

import java.io.Serializable;

import static org.junit.jupiter.api.Assertions.assertSame;

class PlainDubboProviderSerializationTest {

    @Test
    void trustsSerializableTypesDeclaredByTheExportedService() throws Exception {
        PlainDubboProvider.registerServiceSerializationTypes(TypedService.class);
        DefaultSerializeClassChecker checker = DubboRuntimeModel.module()
                .getApplicationModel()
                .getFrameworkModel()
                .getBeanFactory()
                .getBean(DefaultSerializeClassChecker.class);

        assertSame(
                TypedPayload.class,
                checker.loadClass(TypedPayload.class.getClassLoader(), TypedPayload.class.getName()));
    }

    private interface TypedService {
        TypedPayload create(TypedPayload request);
    }

    private record TypedPayload(String value) implements Serializable {}
}
