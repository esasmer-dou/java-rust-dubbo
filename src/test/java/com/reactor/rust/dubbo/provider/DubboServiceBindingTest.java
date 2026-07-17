package com.reactor.rust.dubbo.provider;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DubboServiceBindingTest {

    @Test
    void bindsAnExplicitServiceContract() {
        GreetingService implementation = name -> "Hello " + name;

        DubboServiceBinding<GreetingService> binding =
                DubboServiceBinding.service(GreetingService.class, implementation);

        assertEquals(GreetingService.class, binding.serviceType());
        assertEquals(implementation, binding.implementation());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void rejectsAnImplementationThatDoesNotMatchTheContract() {
        assertThrows(IllegalArgumentException.class,
                () -> DubboServiceBinding.service((Class) GreetingService.class, new Object()));
    }

    private interface GreetingService {
        String greet(String name);
    }
}
