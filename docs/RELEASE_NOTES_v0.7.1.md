# java-rust-dubbo 0.7.1

`0.7.1` aligns the native Dubbo consumer package with `rust-java-rest:4.3.0`.

## User Impact

- Consumer interfaces, generated stubs, static discovery, ZooKeeper discovery, bulkheads, timeout
  controls, Hessian support, and native response handles are unchanged.
- Java handlers and business services stay in Java.
- REST ABI remains `26`, Dubbo ABI remains `7`, and Redis ABI remains `6`.
- Use the DLL/SO carried by the aligned `rust-java-rest:4.3.0` package.

```xml
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>java-rust-dubbo</artifactId>
  <version>0.7.1</version>
</dependency>
```

This is a dependency-alignment patch. It does not expand the runtime feature surface.
