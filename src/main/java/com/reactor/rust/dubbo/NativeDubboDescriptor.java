package com.reactor.rust.dubbo;

final class NativeDubboDescriptor {

    private NativeDubboDescriptor() {}

    static String parameterTypesDesc(Class<?>[] parameterTypes) {
        if (parameterTypes == null || parameterTypes.length == 0) {
            return "";
        }
        StringBuilder builder = new StringBuilder(parameterTypes.length * 8);
        for (Class<?> parameterType : parameterTypes) {
            append(builder, parameterType);
        }
        return builder.toString();
    }

    private static void append(StringBuilder builder, Class<?> type) {
        if (type.isArray()) {
            builder.append('[');
            append(builder, type.getComponentType());
            return;
        }
        if (type == void.class) {
            builder.append('V');
        } else if (type == boolean.class) {
            builder.append('Z');
        } else if (type == byte.class) {
            builder.append('B');
        } else if (type == char.class) {
            builder.append('C');
        } else if (type == short.class) {
            builder.append('S');
        } else if (type == int.class) {
            builder.append('I');
        } else if (type == long.class) {
            builder.append('J');
        } else if (type == float.class) {
            builder.append('F');
        } else if (type == double.class) {
            builder.append('D');
        } else {
            builder.append('L').append(type.getName().replace('.', '/')).append(';');
        }
    }
}
