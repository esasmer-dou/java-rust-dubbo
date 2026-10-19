package com.reactor.rust.dubbo.provider;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProviderDispatcherDecoratorTest {
    @Test void decorationOccursOnceAndLeavesTypedBusinessResultsUnchanged() throws Throwable {
        var registrations = new AtomicInteger();
        var invocations = new AtomicInteger();
        Service implementation = value -> value + 1;
        var plan = new DubboProviderSupport.ServicePlan<>(Service.class, implementation,
                PlainDubboProvider.ServiceExecutionConfig.unbounded());
        var dispatcher = DubboProviderSupport.prepareDispatcher(plan, new ProviderDispatcherDecorator() {
            public <T> ProviderMethodDispatcher<T> decorate(Class<T> type, ProviderMethodDispatcher<T> delegate) {
                assertEquals(Service.class, type);
                registrations.incrementAndGet();
                return (target, method, types, args) -> {
                    invocations.incrementAndGet();
                    return delegate.invoke(target, method, types, args);
                };
            }
        });
        for(int i=0;i<20;i++) assertEquals(i+1, dispatcher.invoke(implementation, "increment",
                new Class<?>[]{int.class}, new Object[]{i}));
        assertEquals(1, registrations.get());
        assertEquals(20, invocations.get());
    }

    @Test void nullDispatcherFailsAtStartup() {
        var plan = new DubboProviderSupport.ServicePlan<>(Service.class, (Service) value -> value,
                PlainDubboProvider.ServiceExecutionConfig.unbounded());
        assertThrows(NullPointerException.class, () -> DubboProviderSupport.prepareDispatcher(plan,
                new ProviderDispatcherDecorator() {
                    public <T> ProviderMethodDispatcher<T> decorate(Class<T> type, ProviderMethodDispatcher<T> delegate) {
                        return null;
                    }
                }));
    }

    public interface Service { int increment(int value); }
}
