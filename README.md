# java-rust-dubbo

Minimal Dubbo consumer adapter for the Java/Rust REST framework.

This project is intentionally small. It lets a Java service running on the Java/Rust REST framework call classic Dubbo providers while keeping the hot HTTP plane in Rust Hyper and the business logic in Java.

## What It Gives You

- Native Dubbo consumer path for low-RSS services.
- Static provider mode with `reactor.dubbo.providers=host:port,...`, so the consumer JVM does not need ZooKeeper, Netty, official Dubbo remoting, or Dubbo `ReferenceConfig`.
- Rust-owned Dubbo TCP framing, bounded connection pool, bounded async queue, keepalive reuse, and response size guard.
- Hot no-arg `byte[]` method path with a Rust-side minimal Hessian2 subset.
- Java API that stays explicit: create a client, create a reference or method invoker, close it on shutdown.
- Optional fallback to the official Dubbo/Netty stack when full Dubbo behavior is required.

## Production Position

BEST: use native transport with static providers in Kubernetes/service-DNS/sidecar-managed environments.

```properties
reactor.dubbo.transport=native
reactor.dubbo.providers=catalog-provider:20880,catalog-provider-2:20880
reactor.dubbo.timeout-ms=1000
reactor.dubbo.retries=0
reactor.dubbo.max-inflight=256
reactor.dubbo.max-response-bytes=8388608
reactor.dubbo.native-connections-per-endpoint=16
reactor.dubbo.native-async-workers=8
reactor.dubbo.native-async-queue-capacity=1024
```

ACCEPTABLE: leave `reactor.dubbo.providers` blank and use ZooKeeper discovery when dynamic provider discovery is worth the extra Java threads/classes/RSS.

ACCEPTABLE: use `reactor.dubbo.transport=official` only when your application explicitly needs official Dubbo governance, routers, metadata center, callbacks, generic invocation, or full compatibility.

ANTI-PATTERN: adding Dubbo Spring Boot starters or `dubbo-config-api` into the hot REST service just to make a simple consumer call. That recreates the memory and startup overhead this adapter avoids.

## Maven

```xml
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>java-rust-dubbo</artifactId>
  <version>3.1.0-rc1</version>
</dependency>
```

The default dependency is intentionally narrow. Official Dubbo, Netty, ZooKeeper, and Hessian Lite are optional and are not pulled into downstream applications unless you explicitly add them.

This adapter expects the Java/Rust framework native library to be available. In `rust-java-rest`, the native library is loaded by the framework. In standalone smoke tests, expose `rust_hyper` through `java.library.path`.

## Quick Start

Create the native consumer once during application startup:

```java
import com.reactor.rust.di.annotation.Bean;
import com.reactor.rust.di.annotation.Configuration;
import com.reactor.rust.di.annotation.PreDestroy;
import com.reactor.rust.dubbo.DubboReferenceSpec;
import com.reactor.rust.dubbo.NativeDubboConsumerClient;
import com.reactor.rust.dubbo.NativeDubboConsumers;
import com.reactor.rust.dubbo.NativeDubboMethodInvoker;

@Configuration
public final class DubboClientConfiguration {
    private final NativeDubboConsumerClient client = NativeDubboConsumers.create();

    @Bean
    public CatalogClient catalogClient() {
        DubboReferenceSpec<CatalogProviderApi> spec = DubboReferenceSpec.of(CatalogProviderApi.class);
        NativeDubboMethodInvoker<byte[]> invoker =
                client.method(spec, "nestedCatalogJson", byte[].class);
        return new CatalogClient(invoker);
    }

    @PreDestroy
    public void shutdown() {
        client.close();
    }
}
```

Use an explicit Java client wrapper on hot paths:

```java
import com.reactor.rust.dubbo.NativeDubboMethodInvoker;

public final class CatalogClient {
    private final NativeDubboMethodInvoker<byte[]> nestedCatalogJson;

    public CatalogClient(NativeDubboMethodInvoker<byte[]> nestedCatalogJson) {
        this.nestedCatalogJson = nestedCatalogJson;
    }

    public byte[] nestedCatalogJson() {
        return nestedCatalogJson.invoke();
    }
}
```

Async invocation is available for handlers that return `CompletionStage`:

```java
public CompletionStage<byte[]> nestedCatalogJsonAsync() {
    return nestedCatalogJson.invokeAsync();
}
```

## Configuration

| Property | Default | Purpose |
| --- | ---: | --- |
| `reactor.dubbo.transport` | `native` | `native` uses Rust data-plane; `official` uses the optional Apache Dubbo stack. |
| `reactor.dubbo.providers` | empty | Static `host:port` list. When set, Java ZooKeeper is not started. |
| `reactor.dubbo.registry-address` | `zookeeper://127.0.0.1:2181` | Used only when static providers are not configured. |
| `reactor.dubbo.registry-root` | `dubbo` | ZooKeeper provider root. |
| `reactor.dubbo.timeout-ms` | `1000` | Per-call timeout. |
| `reactor.dubbo.retries` | `0` | Hidden retries are disabled by default to protect p99. |
| `reactor.dubbo.max-inflight` | `256` | Per-reference native bulkhead. |
| `reactor.dubbo.max-response-bytes` | `8388608` | Native response frame cap. |
| `reactor.dubbo.native-connections-per-endpoint` | `16` | Rust keepalive pool cap per provider endpoint. |
| `reactor.dubbo.native-async-workers` | `2` | Native async worker count. Raise in balanced/throughput profiles. |
| `reactor.dubbo.native-async-queue-capacity` | `128` | Bounded native async queue. Full queue fails fast. |
| `reactor.dubbo.cluster` | `failfast` | Lite adapter supports `failfast` and `failover`. |
| `reactor.dubbo.loadbalance` | `random` | Lite adapter supports `random` and `roundrobin`. |

System properties and environment variables override supplied `Properties`.

## Native vs Official Mode

Native mode avoids loading these into the hot JVM for the supported fast path: official Dubbo `ReferenceConfig`, `RegistryProtocol`, `RegistryDirectory`, Curator, Dubbo cluster wrappers, official remoting, Netty, Java ZooKeeper client, Hessian Lite, Dubbo REST, Triple/protobuf, config-center, metadata-center, metrics/tracing modules, and transport proxy/epoll extras.

Official mode remains available as an explicit fallback. Use it only when you accept the RSS/classpath overhead in exchange for full Dubbo behavior.

## Operational Rules

- Keep `retries=0` unless the provider method is idempotent.
- Keep response sizes bounded with `max-response-bytes`.
- Use static providers or service-DNS when low RSS matters more than ZooKeeper governance.
- Add application-level readiness checks if `check=false` is used.
- Prefer `NativeDubboMethodInvoker` over dynamic proxies on request hot paths.
- Tune `native-async-workers`, `native-connections-per-endpoint`, and route-level bulkheads together; increasing workers alone does not guarantee lower p99.
- Close `NativeDubboConsumerClient` on shutdown so native clients and provider watchers are released.

## Not Supported By Design

- Dubbo config-center and metadata-center.
- Consumer registration under `/consumers`.
- Official Dubbo router/governance rules in native mode.
- Triple, REST protocol, HTTP/2, callback, generic invocation, and full filter chain compatibility.
- Multiplexed Dubbo request demux over one connection. Native mode uses bounded keepalive TCP connections with one in-flight call per connection.

If you need those features, use the official Dubbo stack outside the low-RSS hot REST service, or isolate it behind a sidecar/internal service.

## Build

```powershell
mvn clean verify
```

Release artifacts are produced under `target/`:

- `java-rust-dubbo-3.1.0-rc1.jar`
- `java-rust-dubbo-3.1.0-rc1-sources.jar`

## Documentation

- [Production Guide](docs/PRODUCTION_GUIDE.md)
- [Release Notes](docs/RELEASE_NOTES_v3.1.0-rc1.md)
- [Turkish README](README_TR.md)
