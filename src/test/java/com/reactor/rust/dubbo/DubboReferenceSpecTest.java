package com.reactor.rust.dubbo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DubboReferenceSpecTest {

    @Test
    void keepsOptionalOverridesNullable() {
        DubboReferenceSpec<RemoteOrderService> spec = DubboReferenceSpec.of(RemoteOrderService.class);

        assertEquals(RemoteOrderService.class, spec.serviceInterface());
        assertNull(spec.group());
        assertNull(spec.timeoutMs());
    }

    @Test
    void rejectsConcreteReferenceType() {
        assertThrows(IllegalArgumentException.class, () -> DubboReferenceSpec.of(String.class));
    }

    interface RemoteOrderService {
        String findOrder(String id);
    }
}
