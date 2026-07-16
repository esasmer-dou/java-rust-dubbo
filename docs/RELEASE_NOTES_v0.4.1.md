# java-rust-dubbo 0.4.1

`0.4.1` makes provider capacity explicit and makes native connection reuse safe across provider
restarts. Consumer and provider service contracts remain source-compatible.

## What Changed

- Added a bounded provider executor configuration with explicit core threads, maximum threads,
  queue capacity, keep-alive, and rejection policy.
- Added `reactor.dubbo.native-idle-connection-ttl-ms`. Idle blocking connections expire before they
  can be reused indefinitely.
- Added a low-cost socket liveness check before an idle pooled connection is reused.
- A stale connection is discarded before request bytes are written. A retry is allowed only when a
  reused socket wrote zero request bytes; partial writes are never retried automatically.
- Added metrics for idle expiry, validation, stale discard, and safe retry.
- Advanced the native Dubbo contract to ABI `7`.

## Dependency

```xml
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>java-rust-dubbo</artifactId>
  <version>0.4.1</version>
</dependency>
```

Use native mode with `rust-java-rest:3.4.1`. The aligned runtime reports REST ABI `24`, Dubbo ABI
`7`, and Redis ABI `6`.

## Starting Provider Budget

```properties
reactor.dubbo.provider.executor.core-threads=1
reactor.dubbo.provider.executor.max-threads=8
reactor.dubbo.provider.executor.queue-capacity=16
reactor.dubbo.provider.executor.keep-alive-ms=30000
reactor.dubbo.provider.io-threads=1
```

Keep service and method concurrency limits aligned with the real downstream capacity. For a Hikari
pool of `2`, start a database-backed service near `2` concurrent calls instead of allowing hundreds
of Dubbo/Netty threads to wait for two connections.

## Measured Gate

In the matched local c16 write gate, the bounded provider moved from `93.11 MiB` and `224` threads
to `67.55 MiB` and `26` threads. Throughput increased from `202.15` to `353.39 RPS`, while p99 moved
from `199.86 ms` to `151.21 ms`. This is workload-specific evidence, not a universal performance
claim; the control also had a wider service concurrency gate.

## Compatibility

- Existing consumer/provider builders and launchers remain available.
- Existing Dubbo interfaces and payload contracts remain unchanged.
- Native users must upgrade the shared DLL/SO to the ABI `7` runtime.
