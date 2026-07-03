# java-rust-dubbo 0.2.0

`0.2.0` is the first release line that includes the native response handle path.

This is a minor release, not a patch on top of `0.1.0`, because the runtime behavior and public API
surface changed enough that publishing it under the same `0.1.0` version would be unsafe for
production consumers.

## What Changed For Users

- `NativeResponseHandle` is available for routes that can forward a provider response without
  materializing the full response body as a Java `byte[]`.
- No-argument `byte[]` JSON Dubbo methods can use the native handle path so the REST layer can return
  the response with lower Java heap pressure.
- Byte-array argument command/read methods can use the native path where the method contract matches
  the supported low-overhead shape.
- Pending native Dubbo invocations are completed or failed more explicitly, reducing retained
  futures when a native call is closed or times out.
- Static provider mode and `native-static` remain the smallest dependency surface.
- ZooKeeper discovery and official compatibility paths remain opt-in through the normal artifact.

## Recommended Dependency

```xml
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>java-rust-dubbo</artifactId>
  <version>0.2.0</version>
</dependency>
```

For the smallest static-provider read path:

```xml
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>java-rust-dubbo</artifactId>
  <version>0.2.0</version>
  <classifier>native-static</classifier>
</dependency>
```

Pair it with `rust-java-rest:3.2.5` when the application uses native response handles through the
REST framework.

## Compatibility Notes

- Application imports should stay under `com.reactor.rust.dubbo`.
- `internal.*` packages are still implementation details and can change between releases.
- Native handle mode is additive; existing `byte[]`, typed DTO, and official-mode call paths remain
  available.
- Do not overwrite an existing `0.1.0` package with these changes. Consumers must consciously move to
  `0.2.0` so their Maven cache, Docker image, and native resource package line up.

## Validation

Recommended release gate:

- `mvn clean test` for `java-rust-dubbo`.
- `mvn clean test` for the consumer sample against `java-rust-dubbo:0.2.0`.
- Static provider smoke for the no-argument JSON path.
- Full/static or ZooKeeper smoke when typed DTO or discovery paths are used.
- A short load gate that separates native read, DB read, distributed write, and hot-row write
  workloads.
