package com.reactor.rust.dubbo.provider;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Immutable startup binding. Generated schemas, not reflection, supply the allowed methods. */
public final class NativeProviderBinding<T> {
    final NativeServiceContract<T> contract;
    final T implementation;
    final PlainDubboProvider.ServiceExecutionConfig limits;
    final List<NativeServiceContract.Method> methods;

    public NativeProviderBinding(NativeServiceContract<T> contract, T implementation,
                                 PlainDubboProvider.ServiceExecutionConfig limits) {
        this.contract = Objects.requireNonNull(contract, "contract");
        this.implementation = Objects.requireNonNull(implementation, "implementation");
        this.limits = Objects.requireNonNull(limits, "limits");
        if (!contract.serviceType().isInstance(implementation)) throw new IllegalArgumentException("Wrong implementation type");
        methods = List.copyOf(contract.methods());
        if (methods.isEmpty() || methods.size() > 64) throw new IllegalArgumentException("Service must have 1..64 generated methods");
        var names = new HashSet<String>();
        for (int i = 0; i < methods.size(); i++) {
            var method = methods.get(i);
            if (method.id() != i + 1 || !names.add(method.name())) throw new IllegalArgumentException("Method ids/names must be unique and sequential");
        }
        if (!names.containsAll(limits.methodMaxConcurrentInvocations().keySet())) throw new IllegalArgumentException("Limit names must match generated methods");
        if (contract.group().length() > 256 || contract.version().length() > 256) throw new IllegalArgumentException("Group/version too long");
    }

    Prepared<T> prepare(ProviderDispatcherDecorator decorator) {
        ProviderMethodDispatcher<T> dispatcher = Objects.requireNonNull(contract.dispatcher(), "generated dispatcher");
        if (decorator != null) dispatcher = Objects.requireNonNull(decorator.decorate(contract.serviceType(), dispatcher), "decorated dispatcher");
        return new Prepared<>(this, dispatcher, ProviderConcurrencyGate.forService(contract.serviceType(), limits));
    }

    record Prepared<T>(NativeProviderBinding<T> binding, ProviderMethodDispatcher<T> dispatcher,
                       ProviderConcurrencyGate gate) {
        Object invoke(NativeServiceContract.Method method, Object[] arguments) throws Throwable {
            var permit = gate.acquireOrReject(method.name());
            try { return dispatcher.invoke(binding.implementation, method.name(), method.dispatchTypes(), arguments); }
            finally { gate.release(permit); }
        }
    }
}
