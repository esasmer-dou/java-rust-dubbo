package com.reactor.rust.dubbo.internal.direct;

import com.reactor.rust.dubbo.DubboConsumerException;
import com.reactor.rust.dubbo.internal.runtime.DubboRuntimeModel;

import org.apache.dubbo.common.utils.ReflectUtils;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.model.ServiceModel;
import org.apache.dubbo.rpc.proxy.InvocationUtil;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.concurrent.ConcurrentHashMap;

final class DirectDubboInvocationHandler<T> implements InvocationHandler {

    private final Class<T> serviceInterface;
    private final String interfaceName;
    private final MinimalDubboInvoker<T> invoker;
    private final String protocolServiceKey;
    private final ServiceModel serviceModel;
    private final ConcurrentHashMap<Method, MethodPlan> methodPlans = new ConcurrentHashMap<>();

    DirectDubboInvocationHandler(Class<T> serviceInterface, MinimalDubboInvoker<T> invoker) {
        this.serviceInterface = serviceInterface;
        this.interfaceName = serviceInterface.getName();
        this.invoker = invoker;
        this.protocolServiceKey = invoker.getUrl().getServiceKey();
        this.serviceModel = DubboRuntimeModel.module().registerInternalConsumer(serviceInterface, invoker.getUrl());
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return invokeObjectMethod(method, args);
        }
        if (method.getParameterCount() == 0 && "$destroy".equals(method.getName())) {
            invoker.destroy();
            return null;
        }
        MethodPlan plan = methodPlans.computeIfAbsent(method, this::createMethodPlan);

        RpcInvocation invocation = new RpcInvocation(
                serviceModel,
                plan.method(),
                interfaceName,
                protocolServiceKey,
                args);
        invocation.setParameterTypes(plan.parameterTypes());
        invocation.setParameterTypesDesc(plan.parameterTypesDesc());
        invocation.setAttachment("interface", interfaceName);
        invocation.setAttachment("path", interfaceName);
        invocation.setReturnTypes(plan.returnTypes());
        return InvocationUtil.invoke(invoker, invocation);
    }

    private Object invokeObjectMethod(Method method, Object[] args) {
        return switch (method.getName()) {
            case "toString" -> invoker.toString();
            case "hashCode" -> invoker.hashCode();
            case "equals" -> invoker.equals(args != null && args.length > 0 ? args[0] : null);
            default -> throw new DubboConsumerException("Unsupported Object method: " + method);
        };
    }

    private MethodPlan createMethodPlan(Method method) {
        Class<?>[] parameterTypes = method.getParameterTypes();
        Type[] returnTypes = new Type[] {method.getReturnType(), method.getGenericReturnType()};
        return new MethodPlan(method, parameterTypes, ReflectUtils.getDesc(parameterTypes), returnTypes);
    }

    private record MethodPlan(
            Method method,
            Class<?>[] parameterTypes,
            String parameterTypesDesc,
            Type[] returnTypes) {}
}
