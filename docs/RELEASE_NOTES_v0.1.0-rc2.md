# java-rust-dubbo 0.1.0-rc2

Release candidate focused on source layout hardening and package hygiene.

## What's New For Users

- Public application imports stay under `com.reactor.rust.dubbo`.
- Internal implementation classes are now organized under `com.reactor.rust.dubbo.internal.*`.
- Application code should continue using `DubboConsumerConfig`, `DubboReferenceSpec`, `NativeDubboConsumerClient`, `NativeDubboConsumers`, and `NativeDubboMethodInvoker`.
- No consumer-side API migration is required if your project only imports the public root package.

## Internal Package Layout

- `internal.nativeclient`: native transport reference, Hessian request/response codec, descriptor helper, and native provider watcher.
- `internal.direct`: optional official Dubbo direct invoker path.
- `internal.registry`: ZooKeeper discovery, registry address parsing, and Dubbo URL construction.
- `internal.runtime`: Dubbo runtime model and low-RSS runtime tuning.
- `internal.util`: small runtime utilities.

## Compatibility

- Java 21.
- Compatible with existing `0.1.0-rc1` consumer imports when applications do not import `internal.*`.
- `internal.*` packages are not a stable user API and may change between release candidates.

## Verification

Validated locally with:

```powershell
mvn -q test
mvn -q verify
```

Release artifacts:

- `java-rust-dubbo-0.1.0-rc2.jar`
- `java-rust-dubbo-0.1.0-rc2-sources.jar`
