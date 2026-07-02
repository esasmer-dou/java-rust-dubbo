# java-rust-dubbo 0.1.0

This is the first stable release of `java-rust-dubbo` for Rust-Java REST applications that need a small Dubbo consumer layer.

## What Is Stable Now

- Native Dubbo consumer mode is the default path for low-RSS REST services.
- Static provider mode works without Java ZooKeeper, Netty, or the official Dubbo consumer stack in the hot JVM.
- ZooKeeper discovery remains available when dynamic provider discovery is required.
- Typed Hessian encode/decode is available through the normal artifact for argument-bearing methods and record/list/scalar responses.
- The `native-static` classifier is available for the smallest static-provider, no-arg `byte[]` fast path.
- Native async invocation tracking is bounded and cleaned up on rejection, timeout, completion, and client close.
- Native bridge classloading is safe when REST requests execute on Rust/JNI worker threads with a missing or different context classloader.

## Recommended Production Choices

BEST for low RSS:

```properties
reactor.dubbo.transport=native
reactor.dubbo.runtime-profile=micro-dubbo
reactor.dubbo.providers=catalog-provider:20880
reactor.dubbo.max-inflight=8
reactor.dubbo.native-connections-per-endpoint=2
reactor.dubbo.native-async-workers=2
reactor.dubbo.native-async-queue-capacity=32
```

Use this when Kubernetes Service DNS, static config, or a sidecar-generated provider list can provide the provider address.

BEST when ZooKeeper is required:

```properties
reactor.dubbo.transport=native
reactor.dubbo.runtime-profile=micro-dubbo
reactor.dubbo.registry-address=zookeeper://zookeeper-client.platform.svc.cluster.local:2181
reactor.dubbo.registry-root=dubbo
reactor.dubbo.max-inflight=8
reactor.dubbo.native-connections-per-endpoint=2
```

Use this only when provider discovery must follow ZooKeeper registrations.

ACCEPTABLE for typed methods:

Use the normal artifact and include `hessian-lite` when methods carry arguments or return typed records/lists/scalars. This is more flexible than `native-static`, but it loads more Java classes.

ANTI-PATTERN:

Do not use the official Dubbo/Netty path as the default in a low-memory REST service. Keep it only for compatibility cases where native mode cannot represent the provider contract.

## Maven

Normal artifact:

```xml
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>java-rust-dubbo</artifactId>
  <version>0.1.0</version>
</dependency>
```

Lowest-RSS static-provider artifact:

```xml
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>java-rust-dubbo</artifactId>
  <version>0.1.0</version>
  <classifier>native-static</classifier>
</dependency>
```

## Compatibility

- No Rust native ABI change from `0.1.0-rc4`.
- Existing `0.1.0-rc4` static provider and ZooKeeper discovery properties remain compatible.
- Applications using the normal artifact can upgrade by changing only the Maven version.
- Applications using the `native-static` classifier should rebuild with `0.1.0` so the async pending-call support classes are included in the classifier JAR.

## Validation

- `mvn -q clean test`
- `mvn -q clean install`
- Native-static classifier content check
- Optional dependency boundary test for static native classes without ZooKeeper/Hessian on the classpath
- Sample consumer compile/test against `0.1.0`
