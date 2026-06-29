# java-rust-dubbo 0.1.0-rc4

## What Changed

- Fixed native bridge initialization when a REST request is executed on a Rust/JNI worker thread whose context classloader is not the application classloader.
- `NativeDubboBridge` now resolves the framework `NativeBridge` with its own classloader first, then the system classloader, and only then the thread context classloader.
- This fixes endpoints such as `/api/v1/catalog/dubbo-metrics` returning `Could not initialize class com.reactor.rust.dubbo.NativeDubboBridge` while `/app/health` still works.
- Fixed typed Hessian decode/encode classloading for native Dubbo calls.
- Method codec plans now carry the service/application classloader explicitly, so typed responses such as `CustomerStats`, `CustomerSummary`, and `List<CustomerSummary>` do not degrade to `HashMap` when the request is executed from a worker thread with a missing or wrong context classloader.

## Compatibility

- No public API change.
- No Rust native ABI change.
- Existing static provider and ZooKeeper discovery configuration remains the same.

## Validation

- `mvn -q test` in `java-rust-dubbo`.
- Null context-classloader smoke for `NativeDubboBridge.metricsJson()`.
