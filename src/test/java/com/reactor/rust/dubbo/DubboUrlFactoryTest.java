package com.reactor.rust.dubbo;

import org.apache.dubbo.common.URL;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DubboUrlFactoryTest {

    @Test
    void buildsEncodedProviderPathWithoutCreatingRegistrySideEffects() {
        DubboConsumerConfig config = DubboConsumerConfig.builder()
                .registryRoot("/dubbo/")
                .build();
        DubboReferenceSpec<RemoteBillingService> spec = DubboReferenceSpec.of(RemoteBillingService.class);

        assertEquals("/dubbo/" + URL.encode(RemoteBillingService.class.getName()) + "/providers",
                DubboUrlFactory.providerPath(config, spec));
    }

    @Test
    void filtersProviderByProtocolGroupVersionAndEnabledFlag() {
        DubboConsumerConfig config = DubboConsumerConfig.builder().build();
        DubboReferenceSpec<RemoteBillingService> spec = DubboReferenceSpec.builder(RemoteBillingService.class)
                .group("blue")
                .version("1.0.0")
                .build();

        URL accepted = URL.valueOf("dubbo://127.0.0.1:20880/" + RemoteBillingService.class.getName()
                + "?group=blue&version=1.0.0");
        URL wrongVersion = accepted.addParameter("version", "2.0.0");
        URL disabled = accepted.addParameter("disabled", "true");

        assertTrue(DubboUrlFactory.accepts(config, spec, accepted));
        assertFalse(DubboUrlFactory.accepts(config, spec, wrongVersion));
        assertFalse(DubboUrlFactory.accepts(config, spec, disabled));
    }

    interface RemoteBillingService {
        String findInvoice(String id);
    }
}
