package com.reactor.rust.dubbo.internal.nativeclient;

import com.reactor.rust.dubbo.NativeDubboMethodInvoker;

public interface NativeDubboReferenceHandle<T> extends AutoCloseable {

    void start();

    T proxy();

    <R> NativeDubboMethodInvoker<R> methodInvoker(String methodName, Class<R> returnType, Class<?>... parameterTypes);

    @Override
    void close();
}
