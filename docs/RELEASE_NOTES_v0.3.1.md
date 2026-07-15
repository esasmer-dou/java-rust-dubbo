# java-rust-dubbo 0.3.1

`0.3.1` removes repeated provider bootstrap code while keeping service export and resource ownership
explicit.

## What's New

- Added `DubboProviderApplication.run(...)` and `start(...)` terminal launchers.
- Exposed layered `DubboApplicationProperties` through provider `ModuleContext`.
- The simple launcher applies low-RSS provider defaults and reads registry enablement from properties.
- Added a typed boolean default overload to `DubboApplicationProperties`.
- Kept the builder API available for non-standard embedded lifecycle requirements.

## Recommended Bootstrap

```java
DubboProviderApplication.run(
        "provider.properties",
        "catalog-provider",
        CatalogProviderModule.INSTANCE);
```

## Dependency

```xml
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>java-rust-dubbo</artifactId>
  <version>0.3.1</version>
</dependency>
```

## Compatibility

- Existing consumer, provider, and builder APIs remain available.
- Dubbo native ABI remains `5`; native response behavior is unchanged.
