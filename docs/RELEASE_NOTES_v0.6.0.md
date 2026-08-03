# java-rust-dubbo 0.6.0

`0.6.0` makes multi-interface native Dubbo consumers shorter to configure while keeping REST
handlers, service orchestration, validation, and business decisions in Java.

## What You Use

```java
@EnableNativeDubboClients(discoveryProperty = "app.dubbo.discovery")
@GenerateNativeDubboClient(
        service = CatalogProviderApi.class,
        generatedName = "CatalogClient")
@GenerateNativeDubboClient(
        service = CustomerProviderApi.class,
        generatedName = "CustomerClient")
final class DubboClients {
    private DubboClients() {}
}
```

Inject `CatalogClient` or `CustomerClient` into normal Java handlers. The generated clients share
one bounded transport lifecycle; declaring another interface does not create another native runtime.

## What Is New

- Repeatable `@GenerateNativeDubboClient` declarations.
- `@EnableNativeDubboClients` for one generated lifecycle and one bean per client contract.
- Build-time support for inherited, concrete service methods.
- Build failures for empty client sets, generic contracts, overloaded methods, invalid generated
  names, and unsupported signatures.
- Separate `codegen` artifact; processor classes and service metadata stay out of runtime JARs.
- Generated typed async and native JSON response-handle methods without request-time proxy dispatch.

## Dependency

```xml
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>java-rust-dubbo</artifactId>
  <version>0.6.0</version>
</dependency>
```

Use `rust-java-rest:4.1.0`. Static-provider users may continue to select the `native-static`
classifier.

## Compatibility

Dubbo interfaces, Java handlers, provider contracts, discovery properties, and native Dubbo ABI `7`
are unchanged. Existing manual client definitions remain supported for unusual embedded use cases.
Normal applications should use generated clients.

## Verification

- Library and processor tests passed with no failures or errors.
- Full, native-static, and ZooKeeper consumer profiles passed.
- Runtime and native-static JARs contain no annotation processor metadata.
- Native transport Docker load tests completed without a request-path compatibility regression.
