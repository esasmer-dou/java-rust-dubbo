# java-rust-dubbo 3.1.0-rc1

Release candidate for the minimal Dubbo consumer adapter used by the Java/Rust REST framework.

## What's New For Users

- Add `java-rust-dubbo` as a small Maven dependency when a Java/Rust REST service needs to call Dubbo providers.
- Use `reactor.dubbo.transport=native` to keep the Dubbo data-plane in Rust instead of loading the official Dubbo/Netty client stack into the REST JVM.
- Use `reactor.dubbo.providers=host:port,...` for the lowest-RSS mode. This skips Java ZooKeeper in the consumer process.
- Use `NativeDubboMethodInvoker` for hot request paths instead of dynamic proxy dispatch.
- Use `invokeAsync()` when the REST route returns `CompletionStage`, so the Java handler does not block a request worker while the Dubbo call is in flight.
- Configure bounded native connection and queue limits with `native-connections-per-endpoint`, `native-async-workers`, and `native-async-queue-capacity`.

## Main Changes

- Native Dubbo consumer client API.
- Static provider mode.
- Optional ZooKeeper provider watcher.
- Rust-native async invocation bridge.
- Bounded native queue and connection pool configuration.
- Minimal hot-path Hessian2 support for no-arg `byte[]` methods.
- Explicit method-plan cache through `NativeDubboMethodInvoker`.
- Optional official Dubbo/Netty fallback kept out of downstream runtime by default.
- GitHub Packages Maven publishing metadata.
- Source JAR generation.

## Compatibility

- Java 21.
- Designed for the Java/Rust REST framework native library that exports the required JNI functions.
- Native mode supports classic Dubbo TCP provider calls for the supported method shapes.
- Official mode remains available only when optional dependencies are explicitly provided by the application.

## Known Limits

- Native mode does not implement full Dubbo governance, metadata-center, config-center, callbacks, generic invocation, Triple, REST protocol, or official filter chains.
- Native mode uses bounded keepalive TCP connections, not multiplexed demux over a single connection.
- ZooKeeper discovery adds memory and thread cost; static providers are the recommended low-RSS path.
- Non-`byte[]` or argument-heavy methods may use the Java Hessian codec fallback and carry more allocation cost.

## Verification

Validated locally with:

```powershell
mvn clean verify
```

Release artifacts:

- `java-rust-dubbo-3.1.0-rc1.jar`
- `java-rust-dubbo-3.1.0-rc1-sources.jar`
