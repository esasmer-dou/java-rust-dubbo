package com.reactor.rust.dubbo;

import org.apache.dubbo.common.utils.ReflectUtils;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.model.ServiceModel;
import org.apache.dubbo.rpc.proxy.InvocationUtil;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.Objects;

public final class DubboMethodInvoker<R> {

    private static final Object[] EMPTY_ARGS = new Object[0];

    private final MinimalDubboInvoker<?> invoker;
    private final Method method;
    private final Class<R> returnType;
    private final String interfaceName;
    private final String protocolServiceKey;
    private final ServiceModel serviceModel;
    private final Class<?>[] parameterTypes;
    private final String parameterTypesDesc;
    private final Type[] returnTypes;

    DubboMethodInvoker(
            Class<?> serviceInterface,
            MinimalDubboInvoker<?> invoker,
            Method method,
            Class<R> returnType) {
        this.invoker = Objects.requireNonNull(invoker, "invoker");
        this.method = Objects.requireNonNull(method, "method");
        this.returnType = Objects.requireNonNull(returnType, "returnType");
        this.interfaceName = serviceInterface.getName();
        this.protocolServiceKey = invoker.getUrl().getServiceKey();
        this.serviceModel = DubboRuntimeModel.module().registerInternalConsumer(serviceInterface, invoker.getUrl());
        this.parameterTypes = method.getParameterTypes();
        this.parameterTypesDesc = ReflectUtils.getDesc(parameterTypes);
        this.returnTypes = new Type[] {method.getReturnType(), method.getGenericReturnType()};
    }

    public R invoke() {
        return invokeWithArgs(EMPTY_ARGS);
    }

    public R invoke(Object arg0) {
        return invokeWithArgs(new Object[] {arg0});
    }

    public R invoke(Object... args) {
        return invokeWithArgs(args == null ? EMPTY_ARGS : args);
    }

    private R invokeWithArgs(Object[] args) {
        if (args.length != parameterTypes.length) {
            throw new IllegalArgumentException("Expected " + parameterTypes.length + " arguments for "
                    + interfaceName + "." + method.getName() + " but got " + args.length);
        }
        RpcInvocation invocation = new RpcInvocation(
                serviceModel,
                method,
                interfaceName,
                protocolServiceKey,
                args);
        invocation.setParameterTypes(parameterTypes);
        invocation.setParameterTypesDesc(parameterTypesDesc);
        invocation.setAttachment("interface", interfaceName);
        invocation.setAttachment("path", interfaceName);
        invocation.setReturnTypes(returnTypes);
        try {
            Object result = InvocationUtil.invoke(invoker, invocation);
            if (returnType == Void.TYPE || returnType == Void.class) {
                return null;
            }
            return returnType.cast(result);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new DubboConsumerException("Dubbo invocation failed for "
                    + interfaceName + "." + method.getName(), e);
        }
    }
}
