package com.reactor.rust.dubbo.provider;

public final class DefaultPathProbe {
    public static void main(String[] ignored) throws Throwable {
        ProviderMethodDispatcher<String> call = (target, method, types, args) -> target;
        if (!call.invoke("unchanged", "read", new Class<?>[0], new Object[0]).equals("unchanged")) throw new AssertionError();
        if (!PlainDubboProvider.ServiceExecutionConfig.bounded(1).isBounded()) throw new AssertionError();
        System.out.println("DEFAULT_PATH_PASS");
    }
}
