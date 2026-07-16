# java-rust-dubbo 0.4.0

`0.4.0` reduces native Dubbo thread and connection-pool retention while keeping the Java consumer
and provider APIs explicit and source-compatible.

## What's New

- Added `reactor.dubbo.native-thread-stack-bytes` with a validated `128 KiB..8 MiB` range.
- Set the memory-first default native worker stack to `256 KiB`.
- Made native transport allocation exclusive: `blocking` clients allocate only blocking endpoint
  pools; `tokio-demux` clients allocate only async endpoint pools.
- Exposed blocking and async endpoint-pool counts through native metrics.
- Added repeatable transport A/B benchmark tooling.
- Updated the native bridge to Dubbo ABI `6`.

## Dependency

```xml
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>java-rust-dubbo</artifactId>
  <version>0.4.0</version>
</dependency>
```

Pair native mode with `rust-java-rest:3.4.0`. Do not reuse a DLL/SO from an older release.

## Choosing The Transport

- Use `blocking` for low traffic and the smallest predictable worker surface.
- Use `tokio-demux` for read-heavy concurrent routes only after a container RSS and p99 gate.
- Do not increase worker count, queue capacity, and route concurrency together without measuring
  provider capacity and overload behavior.

## Compatibility

- Existing consumer/provider builders and launchers remain available.
- Existing two- and three-argument `configureAsync` overloads remain available.
- Native users must upgrade to the aligned ABI `6` runtime.
