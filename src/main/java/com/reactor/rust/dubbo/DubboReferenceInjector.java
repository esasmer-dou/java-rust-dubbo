package com.reactor.rust.dubbo;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Objects;

public final class DubboReferenceInjector {

    private DubboReferenceInjector() {}

    public static int inject(Iterable<?> beans, DubboConsumerClient client) {
        Objects.requireNonNull(beans, "beans");
        Objects.requireNonNull(client, "client");
        int injected = 0;
        for (Object bean : beans) {
            if (bean == null) {
                continue;
            }
            injected += injectBean(bean, client);
        }
        return injected;
    }

    public static int inject(Object bean, DubboConsumerClient client) {
        Objects.requireNonNull(bean, "bean");
        Objects.requireNonNull(client, "client");
        return injectBean(bean, client);
    }

    private static int injectBean(Object bean, DubboConsumerClient client) {
        int injected = 0;
        Class<?> type = bean.getClass();
        while (type != null && type != Object.class) {
            for (Field field : type.getDeclaredFields()) {
                DubboReference annotation = field.getAnnotation(DubboReference.class);
                if (annotation == null) {
                    continue;
                }
                validateField(field);
                try {
                    if (field.trySetAccessible()) {
                        Object current = field.get(bean);
                        if (current != null) {
                            continue;
                        }
                        Object proxy = client.get(DubboReferenceSpec.fromAnnotation(field.getType(), annotation));
                        field.set(bean, proxy);
                        client.recordFieldInjected();
                        injected++;
                    } else {
                        throw new DubboConsumerException("Cannot access @DubboReference field "
                                + field.getDeclaringClass().getName() + "." + field.getName());
                    }
                } catch (IllegalAccessException e) {
                    throw new DubboConsumerException("Failed to inject @DubboReference field "
                            + field.getDeclaringClass().getName() + "." + field.getName(), e);
                }
            }
            type = type.getSuperclass();
        }
        return injected;
    }

    private static void validateField(Field field) {
        int modifiers = field.getModifiers();
        if (Modifier.isStatic(modifiers)) {
            throw new IllegalArgumentException("@DubboReference cannot be used on static fields: "
                    + field.getDeclaringClass().getName() + "." + field.getName());
        }
        if (Modifier.isFinal(modifiers)) {
            throw new IllegalArgumentException("@DubboReference cannot be used on final fields: "
                    + field.getDeclaringClass().getName() + "." + field.getName());
        }
        if (!field.getType().isInterface()) {
            throw new IllegalArgumentException("@DubboReference field type must be an interface: "
                    + field.getDeclaringClass().getName() + "." + field.getName());
        }
    }
}
