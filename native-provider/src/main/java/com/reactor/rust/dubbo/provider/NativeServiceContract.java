package com.reactor.rust.dubbo.provider;

import com.reactor.rust.dubbo.runtime.Hessian2Input;
import com.reactor.rust.dubbo.runtime.Hessian2Output;
import java.util.List;

/** Generated compile-time schema and typed calls. No reflective fallback is provided. */
public interface NativeServiceContract<T> {
    Class<T> serviceType();
    String group();
    String version();
    List<Method> methods();
    ProviderMethodDispatcher<T> dispatcher();
    Object[] readArguments(int methodId, Hessian2Input input);
    void writeArguments(int methodId, Hessian2Output output, Object[] arguments);
    Object readResult(int methodId, Hessian2Input input);
    void writeResult(int methodId, Hessian2Output output, Object result);

    record Method(int id, String name, String descriptor, Class<?>[] parameterTypes) {
        public Method {
            if (id < 1 || name == null || name.isBlank() || name.length() > 128
                    || descriptor == null || descriptor.length() > 2048) throw new IllegalArgumentException("Invalid generated method");
            parameterTypes = parameterTypes.clone();
        }
        @Override public Class<?>[] parameterTypes() { return parameterTypes.clone(); }
        Class<?>[] dispatchTypes() { return parameterTypes; }
    }
}
