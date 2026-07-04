# java-rust-dubbo 0.2.1

`0.2.1` keeps the native response-handle line from `0.2.0` and adds public helper APIs that reduce
consumer/provider boilerplate without adding hidden service discovery or auto-export magic.

## What Changed For Users

- `DubboConsumerSupport` is available for property-backed consumer setup.
- `DubboProviderSupport` is available for explicit provider service export, startup logging, and
  lifecycle cleanup.
- `PlainDubboProvider`, `DubboProviderRegistration`, and `ZookeeperDubboProviderRegistration` are now
  public provider support classes.
- Provider interface and method concurrency limits remain property-driven.
- The service list remains explicit in application code; the library does not scan and export random
  interfaces from the classpath.
- The `native-static` classifier now includes the lightweight consumer support API.

## Consumer Example

```java
DubboConsumerSupport support = DubboConsumerSupport
        .fromProperties(PropertiesLoader.getAll())
        .discoveryProperty("sample.dubbo.discovery");

NativeDubboConsumerClient client = NativeDubboConsumers.create(support.config());
DubboReferenceSpec<CatalogService> spec = support.reference(CatalogService.class);
```

## Provider Example

```java
DubboProviderSupport support = DubboProviderSupport.fromProperties(appProperties);
PlainDubboProvider.ProviderConfig config = support.providerConfig(registryEnabled);

List<DubboProviderSupport.ServicePlan<?>> services = List.of(
        support.service(CatalogService.class, catalogService),
        support.service(CustomerQueryService.class, customerQueryService));

List<DubboProviderSupport.ExportedService<?>> exported =
        support.exportAll(config, registration, services);
```

## Recommended Dependency

```xml
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>java-rust-dubbo</artifactId>
  <version>0.2.1</version>
</dependency>
```

For the smallest static-provider read path:

```xml
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>java-rust-dubbo</artifactId>
  <version>0.2.1</version>
  <classifier>native-static</classifier>
</dependency>
```

Pair it with `rust-java-rest:3.2.6` when the application uses the Java/Rust REST framework.

## Compatibility Notes

- Native ABI did not change in this release.
- Existing `0.2.0` consumer call paths remain source-compatible.
- `internal.*` packages are still implementation details.
- Provider helpers are additive. Existing manual provider bootstrap code can keep running.

## Validation

Recommended release gate:

- `mvn clean test` for `java-rust-dubbo`.
- Consumer sample test with `java-rust-dubbo:0.2.1`.
- Provider sample test and profile compile with `java-rust-dubbo:0.2.1`.
- Static-provider and ZooKeeper-discovery smoke in the target environment when those modes are used.
