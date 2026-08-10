# java-rust-dubbo 0.7.0

`0.7.0` aligns native Dubbo consumers with `rust-java-rest:4.2.0` and makes optional client surfaces
declarative at build time.

## What Is New

- `@EnableNativeDubboClients(staticOnly = true)` creates a transport that rejects accidental
  ZooKeeper discovery configuration.
- `@GenerateNativeDubboClient` can conditionally register a generated client with
  `enabledProperty`, `havingValue`, and `matchIfMissing`.
- Runtime annotations required by generated applications remain in the normal/native-static JARs.
- Processor implementation classes and service metadata remain isolated in the build-only
  `codegen` JAR.
- Generated clients continue to use one bounded native transport lifecycle without request-time
  proxy dispatch.

## Aligned Dependency

```xml
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>java-rust-dubbo</artifactId>
  <version>0.7.0</version>
</dependency>
```

Use `rust-java-rest:4.2.0`. Native Dubbo ABI remains `7`; the shared runtime carries REST ABI `26`
and Redis ABI `6`.

## Compatibility

Provider contracts, generated client method signatures, static/ZooKeeper discovery properties, and
Java service orchestration remain compatible. Existing client declarations stay enabled unless a
new conditional property is explicitly configured.
