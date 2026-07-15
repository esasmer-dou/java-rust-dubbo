# java-rust-dubbo 0.3.0

`0.3.0` reduces application boilerplate and hardens provider, registry and native consumer
lifecycle behavior. Java still owns business services; Rust remains the minimal Dubbo data plane
for native consumer profiles.

## What's New

- Added `DubboApplicationProperties` with JVM, environment and external-overlay precedence.
- Added `DubboProviderApplication` for declarative service registration and deterministic resource
  shutdown.
- Added provider-level and method-level concurrency gates with bounded admission.
- Replaced reflective provider dispatch in the hot path with pre-resolved method handles.
- Hardened ZooKeeper registration, reconnect, coalesced refresh and close behavior.
- Hardened pending native invocations, timeout cleanup and legacy decode executor lifecycle.
- Added reusable registry executors and lower-allocation single-provider/native-static paths.

## Dependency

```xml
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>java-rust-dubbo</artifactId>
  <version>0.3.0</version>
</dependency>
```

For a static native consumer with the smallest dependency surface, use the `native-static`
classifier documented in the README.

## Compatibility

- Existing consumer and provider interfaces remain supported.
- Dubbo native ABI remains `5`.
- Existing property keys remain valid; the new declarative application API is additive.
