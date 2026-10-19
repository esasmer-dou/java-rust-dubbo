# Explicit Database Commands

[English](functional-operations.md) | [Turkish](functional-operations.tr.md)

No native ABI or Dubbo wire change.

The JDBC repository exposes `transactions()`. Call it once when constructing
your service. This creates a handle; it does not open a DB connection.
Inside `execute`, pass the supplied `JdbcSession` into cooperating repository methods.
Never open a second connection halfway through the same atomic operation.
Demo schema preparation runs before the first operation, not in a constructor.
Keep dependency readiness checks enabled before accepting production traffic.

Use `PostgresIdempotency` only for commands that need safe retry after an unknown
response. It stores the bounded result in the same PostgreSQL transaction as the change.
Scope keys by trusted tenant, operation, and schema version.
A matching key with different input is a conflict, not a second command.

This adds database work only where explicitly used. It is not an automatic retry layer.
Database applications explicitly add the dependency-free `rust-java-jdbc` module.
It is optional in the cache and Dubbo libraries. A reader or consumer does not inherit it.
See the REST project's [explicit operations guide](https://github.com/esasmer-dou/rust-java-rest/blob/master/docs/explicit-operations.md).
The provider sample demonstrates typed customer status commands.

## Native Callbacks And Provider Buffers

Native async callbacks reuse the JVM attachment of each existing native worker.
Each callback has its own JNI local-reference frame. The frame is released after the callback.
The attachment is released when the worker exits. No extra worker or queue is created.
This applies to both `blocking` and `tokio-demux` transports. Your Java service API stays the same.

`DubboProviderRuntimeTuning.applyLowRssDefaults` also bounds Netty's pooled chunks and thread caches:

```properties
io.netty.allocator.maxOrder=3
io.netty.allocator.smallCacheSize=0
io.netty.allocator.normalCacheSize=0
io.netty.allocator.useCacheForAllThreads=false
```

With the default 8 KiB page, `maxOrder=3` makes a chunk 64 KiB instead of 4 MiB.
These are allocation units, not a total process memory limit. Larger responses still work.
The zero cache sizes prevent each executor thread from retaining its own buffer cache.
Explicit JVM or application overrides remain supported. Apply these settings before Netty starts.
Keep provider concurrency aligned with DB capacity; these settings do not raise that capacity.
