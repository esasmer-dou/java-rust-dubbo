package com.reactor.rust.dubbo.provider;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class MethodHandleProviderDispatcher<T> implements ProviderMethodDispatcher<T> {

    private static final Class<?>[] NO_PARAMETER_TYPES = new Class<?>[0];
    private static final Object[] NO_ARGUMENTS = new Object[0];

    private final String serviceName;
    private final Map<String, MethodPlan[]> methodsByName;

    private MethodHandleProviderDispatcher(String serviceName, Map<String, MethodPlan[]> methodsByName) {
        this.serviceName = serviceName;
        this.methodsByName = methodsByName;
    }

    static <T> MethodHandleProviderDispatcher<T> create(Class<T> serviceType, T service) {
        if (!serviceType.isInterface()) {
            throw new IllegalArgumentException("Dubbo service type must be an interface: " + serviceType.getName());
        }
        Map<String, List<MethodPlan>> grouped = new LinkedHashMap<>();
        for (Method method : serviceType.getMethods()) {
            if (method.getDeclaringClass() == Object.class) {
                continue;
            }
            MethodPlan plan = MethodPlan.create(method, service);
            List<MethodPlan> plans = grouped.computeIfAbsent(method.getName(), ignored -> new ArrayList<>());
            if (plans.stream().noneMatch(existing -> existing.hasParameters(method.getParameterTypes()))) {
                plans.add(plan);
            }
        }
        Map<String, MethodPlan[]> compiled = new LinkedHashMap<>(grouped.size());
        grouped.forEach((name, plans) -> compiled.put(name, plans.toArray(MethodPlan[]::new)));
        return new MethodHandleProviderDispatcher<>(serviceType.getName(), Map.copyOf(compiled));
    }

    @Override
    public Object invoke(T service, String methodName, Class<?>[] parameterTypes, Object[] arguments)
            throws Throwable {
        MethodPlan[] candidates = methodsByName.get(methodName);
        if (candidates == null) {
            throw new NoSuchMethodException(serviceName + "." + methodName);
        }
        Class<?>[] actualTypes = parameterTypes == null ? NO_PARAMETER_TYPES : parameterTypes;
        for (MethodPlan candidate : candidates) {
            if (candidate.hasParameters(actualTypes)) {
                return candidate.invoke(arguments == null ? NO_ARGUMENTS : arguments);
            }
        }
        throw new NoSuchMethodException(serviceName + "." + methodName + Arrays.toString(actualTypes));
    }

    private static final class MethodPlan {

        private final Class<?>[] parameterTypes;
        private final MethodHandle spreader;
        private final boolean returnsVoid;

        private MethodPlan(Class<?>[] parameterTypes, MethodHandle spreader, boolean returnsVoid) {
            this.parameterTypes = parameterTypes;
            this.spreader = spreader;
            this.returnsVoid = returnsVoid;
        }

        static MethodPlan create(Method method, Object service) {
            try {
                boolean returnsVoid = method.getReturnType() == void.class;
                MethodHandle spreader = MethodHandles.publicLookup()
                        .unreflect(method)
                        .bindTo(service)
                        .asSpreader(Object[].class, method.getParameterCount())
                        .asType(MethodType.methodType(
                                returnsVoid ? void.class : Object.class,
                                Object[].class));
                return new MethodPlan(method.getParameterTypes(), spreader, returnsVoid);
            } catch (IllegalAccessException e) {
                throw new IllegalArgumentException(
                        "Dubbo provider method must be public: " + method.toGenericString(),
                        e);
            }
        }

        boolean hasParameters(Class<?>[] actualTypes) {
            return Arrays.equals(parameterTypes, actualTypes);
        }

        Object invoke(Object[] arguments) throws Throwable {
            if (arguments.length != parameterTypes.length) {
                throw new IllegalArgumentException(
                        "Dubbo argument count mismatch: expected=" + parameterTypes.length
                                + " actual=" + arguments.length);
            }
            if (returnsVoid) {
                spreader.invokeExact(arguments);
                return null;
            }
            return (Object) spreader.invokeExact(arguments);
        }
    }
}
