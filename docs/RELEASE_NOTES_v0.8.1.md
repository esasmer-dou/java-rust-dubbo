# java-rust-dubbo 0.8.1

[English](RELEASE_NOTES_v0.8.1.md) | [Türkçe](RELEASE_NOTES_v0.8.1.tr.md)

Plain low-overhead providers intentionally bypass the Apache Dubbo filter chain.
Adding an SPI telemetry filter therefore does not observe their calls.

Use the explicit startup-only `ModuleContext.decorateDispatchers(ProviderDispatcherDecorator)` hook
when a provider needs observation. Configure it before declaring services. It wraps each selected
dispatcher once; it does not restore all filters, add a proxy, or create an executor.
Without a decorator, the existing dispatcher/export path is unchanged.

```java
context.decorateDispatchers(myExplicitDecorator);
FullProviderModule.INSTANCE.configure(context);
```

The sample provider 0.7.1 shows bounded sync/async completion observation with Glowroot 0.7.0.
Java business logic, service arguments/results, transport limits and timeout behavior stay unchanged.
Partial startup cleanup also covers an Error thrown during export/decoration.

Use REST 4.7.0. Native Dubbo ABI remains 7; no new DLL/SO build is needed for this Java-only hook.
Platform BOM 4.7.0 still selects Dubbo 0.8.0. Specify 0.8.1 explicitly in providers that use this new hook.
This patch does not claim a throughput or RSS improvement. Existing 0.8.0 measured exceptions remain documented.
