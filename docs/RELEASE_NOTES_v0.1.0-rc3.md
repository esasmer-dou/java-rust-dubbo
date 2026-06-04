# java-rust-dubbo 0.1.0-rc3

Release candidate focused on reducing the hot JVM surface for native Dubbo consumers.

The public application model is unchanged. Application code should keep using the public classes
under `com.reactor.rust.dubbo`, especially `DubboConsumerConfig`, `DubboReferenceSpec`,
`NativeDubboConsumerClient`, `NativeDubboConsumers`, and `NativeDubboMethodInvoker`.

## What's New For Users

- Added a stricter `native-static` classifier for the lowest-RSS static-provider native path.
- The `native-static` artifact keeps only the classes needed for static provider, native transport,
  no-argument `byte[]` fast-path calls, and bounded runtime tuning.
- Java ZooKeeper discovery and Hessian/official-Dubbo compatibility paths stay in the normal artifact
  and are not required by the smallest static-provider runtime.
- Native static references are now modeled explicitly, so the client can avoid reflective or optional
  discovery paths when `reactor.dubbo.providers=host:port` is configured.
- Added optional dependency boundary tests to catch accidental Hessian/ZooKeeper loading in the
  native-static surface.
- Release assets now include the `java-rust-dubbo-0.1.0-rc3-native-static.jar` classifier jar.

## Maven

Normal artifact:

```xml
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>java-rust-dubbo</artifactId>
  <version>0.1.0-rc3</version>
</dependency>
```

Lowest-RSS static-provider native artifact:

```xml
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>java-rust-dubbo</artifactId>
  <version>0.1.0-rc3</version>
  <classifier>native-static</classifier>
</dependency>
```

Use `native-static` only when all of these are true:

- `reactor.dubbo.transport=native`
- `reactor.dubbo.providers=host:port,...`
- hot calls are no-argument native calls returning `byte[]`
- the consumer does not need Java ZooKeeper discovery
- the consumer does not need Hessian request argument encode/decode
- the consumer does not need official Dubbo governance behavior

If you use ZooKeeper discovery, argument-bearing methods, typed DTO decoding, or official Dubbo
compatibility, use the normal artifact.

## Configuration Guidance

Smallest static-provider setup:

```properties
reactor.dubbo.transport=native
reactor.dubbo.providers=catalog-provider:20880
reactor.dubbo.retries=0
reactor.dubbo.timeout-ms=800
reactor.dubbo.max-inflight=32
reactor.dubbo.native-connections-per-endpoint=1
reactor.dubbo.native-async-workers=1
reactor.dubbo.native-async-queue-capacity=32
```

For the Rust-Java REST framework, pair it with:

```properties
reactor.runtime.profile=micro-dubbo
reactor.dubbo.enabled=true
```

## Compatibility

- Java 21.
- Compatible with existing `0.1.0-rc2` public root-package imports.
- `internal.*` packages remain implementation details and are not a stable application API.
- The normal artifact still keeps compatibility paths for ZooKeeper discovery and Hessian-backed
  argument calls.

## Verification

Validated locally with:

```powershell
mvn -q test
mvn -q verify
```

Additional checks:

- `native-static` jar content check.
- Optional dependency boundary tests.
- `rest-sample-dubbo-consumer` test suite with `java-rust-dubbo 0.1.0-rc3`.
- `rest-sample-dubbo-consumer` `native-static-consumer` profile test.

Release artifacts:

- `java-rust-dubbo-0.1.0-rc3.jar`
- `java-rust-dubbo-0.1.0-rc3-native-static.jar`
- `java-rust-dubbo-0.1.0-rc3-sources.jar`
