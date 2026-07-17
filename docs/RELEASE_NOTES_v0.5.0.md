# java-rust-dubbo 0.5.0

`0.5.0` makes the normal consumer and provider setup declarative while keeping business services and
handler logic in Java and the lightweight Dubbo data plane in Rust.

## What Users Get

- `@GenerateNativeDubboClient` build-time generation for typed async calls and native JSON response
  handles without per-request reflection or proxy dispatch.
- `DubboServiceBinding` for concise, explicit provider registration.
- Reusable bounded read-retry policy with predictable timeout and failure behavior.
- A separate `codegen` JAR used only by Maven during compilation.
- Artifact checks that keep generated processors out of runtime and native-static JARs.

## Dependency

```xml
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>java-rust-dubbo</artifactId>
  <version>0.5.0</version>
</dependency>
```

Use the processor only during compilation:

```xml
<path>
  <groupId>com.reactor</groupId>
  <artifactId>java-rust-dubbo</artifactId>
  <version>0.5.0</version>
  <classifier>codegen</classifier>
</path>
```

The aligned REST framework is `rust-java-rest:4.0.0`. Dubbo native ABI remains `7`; no new DLL or SO
contract is introduced in this release.

## Application Model

- Java still owns REST handlers, validation, service interfaces, provider implementations, database
  calls, and business decisions.
- Rust owns the bounded native Dubbo connection, request, response-handle, and transport path.
- Static provider mode avoids ZooKeeper client overhead when Kubernetes Service DNS already provides
  discovery and load balancing.
- ZooKeeper mode remains available when interface-aware registry behavior and provider change
  notifications are required.

Generated clients replace handwritten sample wrappers; they do not change the Dubbo service
contract or require business logic to move out of Java.
