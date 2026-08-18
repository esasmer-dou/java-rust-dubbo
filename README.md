# java-rust-dubbo

[English](README.md) | [Türkçe](README.tr.md)

[![Version](https://img.shields.io/badge/version-0.7.3-blue.svg)](https://github.com/esasmer-dou/java-rust-dubbo/releases/tag/v0.7.3)
[![REST line](https://img.shields.io/badge/rust--java--rest-4.5.5-green.svg)](https://github.com/esasmer-dou/rust-java-rest/releases/tag/v4.5.5)

`java-rust-dubbo` is a small Dubbo consumer library for the Java/Rust REST framework.

Use it when your REST application is written in Java, your HTTP server is handled by the Rust native layer, and you only need to call Dubbo providers from your Java services without bringing a full Spring Boot or full Apache Dubbo runtime into the REST process.

The library keeps the programming model simple:

- Your handlers, services, and components stay in Java.
- The HTTP I/O plane stays in Rust Hyper through the Java/Rust REST framework.
- Dubbo calls can use a Rust native transport for lower JVM RSS.
- ZooKeeper and the official Dubbo/Netty client stack are optional, not default requirements.

The current aligned release is `java-rust-dubbo:0.7.3` with `rust-java-rest:4.5.5`. It keeps one
declarative client set, one shared bounded transport lifecycle, repeatable generated client
declarations, and build-time validation. Existing provider restart handling and the exclusive
`blocking` or `tokio-demux` transport planes remain in place.

## Start Here

| I need... | Start with... |
| --- | --- |
| Lowest-RSS consumer behind Kubernetes Service DNS | Generated client, native static discovery, and `tokio-demux` |
| ZooKeeper provider discovery without official Dubbo data plane | Generated client, ZooKeeper discovery, and native transport |
| Full Dubbo governance and compatibility | Official mode, accepting its larger JVM surface |

For an executable consumer/provider pair, use
[`rest-sample-dubbo-consumer`](https://github.com/esasmer-dou/rest-sample-dubbo-consumer) and
[`rest-sample-dubbo-provider`](https://github.com/esasmer-dou/rest-sample-dubbo-provider).

## Contents

- [Five-minute decision](#five-minute-decision)
- [Maven dependency](#maven)
- [Public API boundary](#public-api-boundary)
- [Generated client quick start](#quick-start)
- [Configuration reference](#configuration-reference)
- [Starting profiles](#suggested-starting-profiles)
- [Native and official modes](#native-mode-vs-official-mode)
- [Native-mode limits](#current-native-mode-limits)
- [Build and artifacts](#build)

## Five-Minute Decision

| Your environment | Use | JVM surface |
| --- | --- | --- |
| Kubernetes Service DNS or known provider address | Native static discovery | No official Dubbo, Netty, or ZooKeeper client runtime |
| Provider list must be watched in ZooKeeper | Native transport plus ZooKeeper discovery | ZooKeeper client is loaded; official Dubbo data plane is still avoided |
| Full Dubbo governance is mandatory | Official mode | Full Dubbo/Netty runtime and its larger thread/class surface |

For the smallest production consumer, start with `rust-java-starter-dubbo`, generated clients, static
Service DNS, `tokio-demux`, and a bounded per-route admission limit. Keep business decisions and
typed interfaces in Java. The generated client removes dynamic proxy and repeated method-plan work.

Do not enable ZooKeeper only because the provider has multiple replicas. A Kubernetes Service can
load-balance one stable DNS name across those replicas. Use ZooKeeper when its registry semantics,
provider metadata, or cross-platform discovery are real requirements.

## Request Flow

```mermaid
flowchart LR
    HTTP["HTTP client"] --> RH["Rust Hyper"]
    RH --> J["Java handler and service"]
    J --> C["Generated Dubbo client"]
    C --> NT["Bounded Rust Dubbo transport"]
    NT --> P["Dubbo provider"]
```

Java owns the service contract, validation, business decision, and response choice. Rust owns the
bounded client transport, connection reuse, timeout, and optional native response handle. Static
discovery does not mean one provider pod; the address can be a Kubernetes Service that fronts many
replicas.

## When To Use It

Recommended for:

- REST services that need to call classic Dubbo providers.
- Low-RSS Java services where loading the full Dubbo client stack is too expensive.
- Kubernetes deployments where provider addresses can come from service DNS, config, or a sidecar-generated provider list.
- Read-heavy or JSON-returning Dubbo methods where the provider can return a `byte[]` JSON body.
- Java/Rust REST framework applications that want explicit setup instead of auto-configuration magic.

Use the official Dubbo stack instead when you need full Dubbo governance, config-center, metadata-center, official routers, callbacks, generic invocation, Triple, REST protocol, or full filter-chain compatibility.

## Maven

```xml
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>java-rust-dubbo</artifactId>
  <version>0.7.3</version>
</dependency>
```

This artifact is published to GitHub Packages, not Maven Central. If the repository or package is private, the consuming project must also declare the GitHub Packages Maven repository and authenticate with a GitHub personal access token.

Add this repository to the consuming project's `pom.xml`:

```xml
<repositories>
  <repository>
    <id>github</id>
    <name>GitHub Packages</name>
    <url>https://maven.pkg.github.com/esasmer-dou/java-rust-dubbo</url>
  </repository>
</repositories>
```

Then add credentials to the consumer machine's `~/.m2/settings.xml`. The `<id>` must match the repository id above:

```xml
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.0.0 https://maven.apache.org/xsd/settings-1.0.0.xsd">
  <servers>
    <server>
      <id>github</id>
      <username>YOUR_GITHUB_USERNAME</username>
      <password>${env.GITHUB_PACKAGES_TOKEN}</password>
    </server>
  </servers>
</settings>
```

The token must be a classic GitHub PAT with `read:packages`. For private repositories, also grant repository access to the private repo, commonly through the `repo` scope. If your organization uses SSO, authorize the token for the organization. Do not put the token directly in `pom.xml`.

If a collaborator can read the repository but still receives `401` or `404` from Maven, check the package settings on GitHub. The package should either inherit access from `esasmer-dou/java-rust-dubbo`, or the collaborator/team should be granted at least `Read` access to the package.

In native mode this dependency is intentionally small. It does not pull ZooKeeper, Netty, Hessian Lite, or the official Dubbo client stack into your application unless you choose to add and use the official mode.

For the smallest static-provider native setup, use the `native-static` classifier instead of the full JAR:

```xml
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>java-rust-dubbo</artifactId>
  <version>0.7.3</version>
  <classifier>native-static</classifier>
</dependency>
```

Use this classifier only when all of these are true:

- You use `reactor.dubbo.transport=native`.
- You set `reactor.dubbo.providers=host:port,...`.
- Your hot Dubbo method is the low-overhead no-argument `byte[]` path.
- You do not need Java ZooKeeper discovery, official Dubbo mode, or Java Hessian argument encode/decode in the consumer.

If you need ZooKeeper discovery, argument-bearing Dubbo methods, DTO decoding, official Dubbo compatibility, or full governance behavior, use the normal dependency without the classifier. The normal artifact keeps those compatibility paths available.

The Java/Rust framework native library must also be present. In `rust-java-rest`, the framework loads that native library for you. In standalone tests, make sure `rust_hyper` is available through `java.library.path`.

Native Dubbo transport requires Dubbo native ABI `7`. The current aligned source runtime
uses REST ABI `29`, Redis ABI `6`, and Glowroot ABI `3`. The published Maven version shown in this
guide is `rust-java-rest:4.5.5`; use the native artifact packaged with the same coordinated ABI line.
Framework startup verifies the packaged source revision and platform hash. `NativeDubboBridge` also
checks the Dubbo ABI before the first native client is created. Do not copy a DLL/SO from an older
framework release into a newer image.

## Public API Boundary

Use the public classes under `com.reactor.rust.dubbo`, `com.reactor.rust.dubbo.config`,
`com.reactor.rust.dubbo.support`, and `com.reactor.rust.dubbo.provider` in application code.

Common consumer classes:

- `DubboConsumerConfig`
- `DubboReferenceSpec`
- `NativeDubboConsumerClient`
- `NativeDubboConsumers`
- `NativeDubboMethodInvoker`
- `DubboConsumerSupport`
- `com.reactor.rust.dubbo.codegen.GenerateNativeDubboClient` from the build-only `codegen` classifier

Common provider classes:

- `DubboApplicationProperties`
- `DubboProviderApplication`
- `DubboProviderRuntimeTuning`
- `DubboProviderSupport`
- `DubboServiceBinding`
- `PlainDubboProvider`
- `ZookeeperDubboProviderRegistration`
- `com.reactor.rust.dubbo.provider.jdbc.JdbcRepository`
- `com.reactor.rust.dubbo.provider.jdbc.HikariDataSources`

Classes under `com.reactor.rust.dubbo.internal.*` are implementation details. They are separated by responsibility so the runtime is easier to maintain:

- `internal.nativeclient`: native Dubbo transport reference, codec, descriptor, and native provider watcher.
- `internal.direct`: optional official Dubbo direct invoker path.
- `internal.registry`: ZooKeeper provider discovery and Dubbo URL helpers.
- `internal.runtime`: Dubbo runtime model and low-RSS Netty tuning.
- `internal.util`: small runtime utilities.

Do not import `internal.*` from your service. Those packages can change between minor or patch releases without source compatibility guarantees. Source compatibility is guaranteed only for the public API listed above.

## Declarative Consumer And Provider Helpers

`DubboConsumerSupport` and `DubboProviderApplication` remove repeated configuration and lifecycle
code. They do not discover business services automatically. Configuration selects the active
runtime surface, while your code still lists every provider interface and consumer adapter
explicitly.

Recommended consumer example:

```java
@GenerateNativeDubboClient(
        service = CatalogService.class,
        generatedName = "CatalogClient",
        retryReads = true,
        retryProperty = "catalog.read-retry-on-io-error",
        exposeMetrics = true)
final class CatalogClientDefinition {
    private CatalogClientDefinition() {}
}
```

The annotation processor generates `CatalogClient.create(client, support)` and one exact async
method per Dubbo interface method. A `byte[]` return also gets a `...NativeJsonAsync()` method that
can keep the response body in Rust native memory. Method descriptors, return types, group, version,
retry policy, and optional metrics access are fixed at build time. The generated wrapper does not use
the dynamic proxy path. `NativeDubboConsumerClient` resolves each Java interface `Method` once while
the client is created; request handling does not use proxy dispatch, method-name lookup, or reflection.

Provider example:

```java
public final class CatalogProviderApplication {
    public static void main(String[] args) throws Exception {
        DubboProviderApplication.run(
                "provider.properties",
                "catalog-provider",
                CatalogProviderModule.INSTANCE);
    }
}
```

The named module contains only the explicit resource and service plan:

```java
public void configure(DubboProviderApplication.ModuleContext context) {
    CatalogRepository repository = context.manage(
            CatalogRepository.fromProperties(context.properties()));
    context.services(
            service(CatalogService.class, new CatalogServiceImpl(repository)),
            service(CustomerQueryService.class, new CustomerQueryServiceImpl(repository)));
}
```

The application declares the service and resource. The library owns export, registry registration,
low-RSS provider defaults, startup rollback, shutdown hooks, and reverse-order resource cleanup.
The builder remains available for unusual embedded lifecycle requirements.

Declarative providers also get a bounded Dubbo/Netty executor. These properties are mapped to the
exported Dubbo URL before the server starts:

```properties
dubbo.provider.executor.thread-pool=eager
dubbo.provider.executor.core-threads=1
dubbo.provider.executor.max-threads=8
dubbo.provider.executor.queue-capacity=16
dubbo.provider.executor.idle-timeout-ms=30000
dubbo.provider.executor.io-threads=1
```

This avoids Dubbo's default `200` handler-thread ceiling in small pods. `cached` is rejected because
it is not a bounded production choice. Direct callers of the six-argument `ProviderConfig`
constructor retain Dubbo defaults; `DubboProviderSupport` and `DubboProviderApplication` apply the
bounded configuration above. Override it only with thread, RSS, p99, and rejection evidence.

BEST: keep the service list explicit and move only duplicated lifecycle code to these helpers.
ANTI-PATTERN: adding an automatic provider scanner that exports every interface on the classpath.

`DubboServiceBinding.service(...)` is startup metadata only. It validates that the implementation
matches the interface and registers the explicit list before Dubbo starts. It does not add a wrapper,
lookup, allocation, or dispatch layer to a provider invocation.

Provider-side DB helpers are optional. Use them when a plain Dubbo provider needs the same
low-boilerplate JDBC/Hikari lifecycle pattern as the sample provider. They do not generate SQL and
they do not map rows automatically. Your provider still owns SQL, indexes, row mapping, transaction
boundaries, and write idempotency. Hikari pool tuning keys have small built-in defaults, so a
provider can start with only JDBC URL, driver, username and password.

## Quick Start

This section shows the complete flow for adding a Dubbo consumer to a Java/Rust REST application.

### 1. Add The Dependency

Add the Maven dependency shown above to your application.

If your application pulls from the private GitHub package, also add the GitHub Packages repository and `~/.m2/settings.xml` credentials from the Maven section. Adding only the dependency is not enough because Maven Central does not host this artifact.

If your project is already using the Java/Rust REST framework, the native library is usually loaded by the framework. You do not need a Dubbo Spring Boot starter.

### 2. Add Basic Properties

For the lowest-RSS setup, start with native transport and static providers:

```properties
reactor.runtime.profile=micro-dubbo
reactor.dubbo.enabled=true
reactor.dubbo.transport=native
reactor.dubbo.runtime-profile=micro-dubbo
reactor.dubbo.providers=catalog-provider:20880
reactor.dubbo.timeout-ms=800
reactor.dubbo.retries=0
reactor.dubbo.max-inflight=32
reactor.dubbo.max-response-bytes=8388608
reactor.dubbo.native-connections-per-endpoint=1
reactor.dubbo.native-max-idle-connections-per-endpoint=1
reactor.dubbo.native-idle-connection-ttl-ms=30000
reactor.dubbo.native-async-workers=1
reactor.dubbo.native-async-queue-capacity=32
```

What this means:

- `transport=native` tells the library to use the Rust Dubbo data-plane.
- `runtime-profile=micro-dubbo` keeps native workers, queues, refresh queues, and Netty fallback settings narrow.
- `providers=host:port` tells `NativeDubboConsumerClient` where the provider is. With this set, the native low-RSS path does not start Java ZooKeeper.
- `retries=0` keeps latency predictable and avoids duplicate provider calls.
- `max-inflight` and native queue settings prevent unlimited memory growth under load.

If you leave `reactor.dubbo.providers` empty, `NativeDubboConsumerClient` uses ZooKeeper discovery. That is supported, but it adds Java ZooKeeper classes and at least a small discovery thread footprint. Use it when provider failover must come from ZooKeeper; otherwise prefer static providers, Kubernetes service DNS, or a sidecar-generated provider list.

For very small pods, also tune the host JVM. The library can keep Dubbo small, but OpenJ9 still needs
an explicit small CPU view to avoid extra JVM worker/JIT footprint:

```bash
-Xms8m -Xmx48m -Xss256k -Xquickstart -Xtune:virtualized -Xshareclasses:none -XX:ActiveProcessorCount=1
```

For very low traffic services only, adding `-Xnojit` can reduce RSS further. It is not a general
throughput default; measure RPC p99 and CPU before using it in production.

### 3. Define The Dubbo Service Interface

Use the same interface name and method signature that the provider exports.

For the fastest current native path, a no-argument method returning `byte[]` is preferred when the provider can return ready-to-send JSON:

```java
package com.example.catalog;

public interface CatalogProviderApi {
    byte[] nestedCatalogJson();
}
```

The returned `byte[]` can be JSON, MessagePack, protobuf bytes, or any binary payload your HTTP layer knows how to represent.

For command/read-with-parameter routes where the request body is already JSON, the native path also supports these low-overhead signatures:

```java
public interface CustomerCommandApi {
    byte[] createCustomer(byte[] commandJson);

    byte[] patchCustomer(long customerId, byte[] commandJson);
}
```

These signatures keep request encoding in the Rust Hessian subset and keep the provider JSON response as a native HTTP body handle. Use typed record/list methods only when the consumer must inspect domain fields.

For REST JSON pass-through, prefer the native HTTP response handle path when possible:

```java
public CompletableFuture<ResponseEntity<RawResponse>> catalog() {
    return invoker.invokeNativeJsonResponseAsync()
            .thenApply(handle -> ResponseEntity.ok(RawResponse.nativeResponse(handle.nativeId())));
}
```

This keeps the provider JSON body in Rust native memory and sends only a small response id through Java. Use the older `invokeAsync().thenApply(bytes -> RawResponse.json(bytes))` path when the Java handler must inspect, transform, validate, or log the response bytes.

### 4. Generate The Native Client At Build Time (Recommended)

Add the small build-only classifier to the consuming application. `provided` keeps it out of the
runtime dependency surface:

```xml
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>java-rust-dubbo</artifactId>
  <version>0.7.3</version>
  <classifier>codegen</classifier>
  <scope>provided</scope>
</dependency>
```

Add the build-only classifier to `annotationProcessorPaths`:

```xml
<annotationProcessorPaths>
  <path>
    <groupId>com.reactor</groupId>
    <artifactId>java-rust-dubbo</artifactId>
    <version>0.7.3</version>
    <classifier>codegen</classifier>
  </path>
</annotationProcessorPaths>
```

The codegen JAR discovers its processor automatically. Processor classes and service metadata are not
present in the production JAR.

Declare the contract once:

```java
@EnableNativeDubboClients(discoveryProperty = "app.dubbo.discovery")
@GenerateNativeDubboClient(
        service = CatalogProviderApi.class,
        generatedName = "CatalogClient",
        retryReads = true,
        retryProperty = "catalog.read-retry-on-io-error",
        exposeMetrics = true,
        group = "catalog",
        version = "1.0.0")
@GenerateNativeDubboClient(
        service = CustomerProviderApi.class,
        generatedName = "CustomerClient",
        enabledProperty = "sample.consumer.surface",
        havingValue = "full",
        matchIfMissing = true)
final class DubboClients {
    private DubboClients() {}
}
```

Inject the generated client into the handler:

```java
@RestController("/api/v1/catalog")
final class CatalogHandler {
    private final CatalogClient catalog;

    CatalogHandler(CatalogClient catalog) {
        this.catalog = catalog;
    }
}
```

`@EnableNativeDubboClients` generates one bounded transport lifecycle and one bean for each declared
client. Repeating `@GenerateNativeDubboClient` does not create one transport per interface.
Use it together with at least one `@GenerateNativeDubboClient` declaration. The build fails early if
the enable annotation has no client contract or if a generated configuration name is not a valid
Java identifier.

`enabledProperty`, `havingValue`, and `matchIfMissing` control client-bean registration at startup.
The generated bean method carries the same framework condition as the handler that needs it. A
disabled client is not created and does not add a branch to every RPC call. Inject the client through
`Optional<T>` only when the owning component itself must remain active in both modes.

For the smallest static-provider artifact, make the transport contract explicit:

```java
@EnableNativeDubboClients(
        discoveryProperty = "sample.dubbo.discovery",
        staticOnly = true)
@GenerateNativeDubboClient(
        service = CatalogProviderApi.class,
        generatedName = "CatalogClient")
final class NativeStaticClients {}
```

`staticOnly=true` rejects ZooKeeper discovery during startup. Pair it with the `native-static`
classifier and a Maven profile that physically excludes full-Dubbo and ZooKeeper sources. A runtime
property alone is useful for one general artifact, but it cannot remove unused classes from that
artifact.

The generated class exposes exact methods such as `nestedCatalogJsonAsync()` and, for a `byte[]`
result, `nestedCatalogJsonNativeJsonAsync()`. The latter returns `NativeResponseHandle` and avoids
materializing the provider JSON body as a Java `byte[]` when the REST endpoint only passes it through.

BEST: use generation for stable Dubbo interfaces. It removes handwritten descriptor and retry
boilerplate without adding runtime magic. ACCEPTABLE: use the manual API below for dynamic or unusual
embedded integrations. ANTI-PATTERN: create a reflective proxy or resolve method descriptors on every
request.

The generator includes methods inherited from parent interfaces. Contract validation also runs at
build time. Overloaded methods, `void` methods, generic service interfaces, and unresolved generic
method types are rejected with a compiler error. Keep the Dubbo transport contract explicit and
non-generic; use concrete records, primitives, strings, collections, or `byte[]` payloads.

### 5. Manual Client Wiring (Supported Alternative)

Create the consumer once and reuse it. Do not create a Dubbo client per request.

```java
import com.example.catalog.CatalogProviderApi;
import com.reactor.rust.di.annotation.Bean;
import com.reactor.rust.di.annotation.Configuration;
import com.reactor.rust.di.annotation.PreDestroy;
import com.reactor.rust.dubbo.DubboReferenceSpec;
import com.reactor.rust.dubbo.NativeDubboConsumerClient;
import com.reactor.rust.dubbo.NativeDubboConsumers;
import com.reactor.rust.dubbo.NativeDubboMethodInvoker;
import com.reactor.rust.dubbo.NativeResponseHandle;

@Configuration
public final class DubboClientConfiguration {
    private final NativeDubboConsumerClient client = NativeDubboConsumers.create();

    @Bean
    public CatalogClient catalogClient() {
        DubboReferenceSpec<CatalogProviderApi> spec = DubboReferenceSpec
                .builder(CatalogProviderApi.class)
                .timeoutMs(1000)
                .retries(0)
                .check(false)
                .lazy(true)
                .build();

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

Important points:

- The `NativeDubboConsumerClient` is application-scoped.
- The method invoker is created once and reused.
- `client.close()` releases native clients, connections, and watcher resources.

### 6. Wrap The Invoker In A Java Client

This wrapper keeps the rest of your application clean. Your handlers and services do not need to know about Dubbo internals.

```java
import com.reactor.rust.dubbo.NativeDubboMethodInvoker;
import com.reactor.rust.dubbo.NativeResponseHandle;

import java.util.concurrent.CompletableFuture;

public final class CatalogClient {
    private final NativeDubboMethodInvoker<byte[]> nestedCatalogJson;

    public CatalogClient(NativeDubboMethodInvoker<byte[]> nestedCatalogJson) {
        this.nestedCatalogJson = nestedCatalogJson;
    }

    public byte[] nestedCatalogJson() {
        return nestedCatalogJson.invoke();
    }

    public CompletableFuture<byte[]> nestedCatalogJsonAsync() {
        return nestedCatalogJson.invokeAsync();
    }

    public CompletableFuture<NativeResponseHandle> nestedCatalogNativeJsonAsync() {
        return nestedCatalogJson.invokeNativeJsonResponseAsync();
    }
}
```

Use `invoke()` for simple synchronous service code.

Use `invokeAsync()` when your REST framework handler can return `CompletionStage`. That keeps request workers from waiting on the Dubbo response.

### 7. Use It From A Handler Or Service

Example with a raw JSON response:

```java
import com.reactor.rust.annotations.RustRoute;
import com.reactor.rust.di.annotation.Autowired;
import com.reactor.rust.http.RawResponse;
import com.reactor.rust.http.ResponseEntity;

import java.util.concurrent.CompletableFuture;

public final class CatalogHandler {
    @Autowired
    private CatalogClient catalogClient;

    @RustRoute(
            method = "GET",
            path = "/api/v1/catalog",
            responseType = RawResponse.class
    )
    public CompletableFuture<ResponseEntity<RawResponse>> catalog() {
        return catalogClient.nestedCatalogNativeJsonAsync()
                .thenApply(handle -> ResponseEntity.ok(RawResponse.nativeResponse(handle.nativeId())));
    }
}
```

If the provider is down or overloaded, handle the failed future and return a controlled HTTP response from your application layer.

### 8. Add A Route-Level Limit

Native limits protect the Dubbo client. Your HTTP route should also have its own concurrency policy.

Use a small route-level bulkhead when low RSS matters. Use a larger but still bounded limit for balanced throughput. Avoid unbounded queues.

### 9. Verify Before Production

Before shipping:

- Start the app with Dubbo disabled and measure RSS.
- Start the app with Dubbo enabled and measure RSS.
- Call the Dubbo endpoint under c64, c256, c512, and c1000 load.
- Restart the provider and confirm reconnect behavior.
- Make the provider slow and confirm timeout behavior.
- Confirm shutdown releases the native client.

## Configuration Reference

All properties start with `reactor.dubbo.`.

Values can come from a supplied `Properties` object, Java system properties, or environment variables. System properties and environment variables override the supplied `Properties`.

For environment variables, use uppercase and replace `.` / `-` with `_`.

Example:

```text
REACTOR_DUBBO_TIMEOUT_MS=1000
REACTOR_DUBBO_NATIVE_ASYNC_WORKERS=8
```

### Basic Properties

| Property | Default | What It Does | When To Change It |
| --- | ---: | --- | --- |
| `reactor.dubbo.application-name` | `rust-java-rest-dubbo-consumer` | Name used by the consumer. Mostly useful for diagnostics and official-mode metadata. | Change it to your service name, for example `orders-api`. |
| `reactor.dubbo.transport` | `native` | Chooses the transport. `native` uses Rust; `official` uses the optional Apache Dubbo stack. | Keep `native` for low RSS. Use `official` only when you need full Dubbo behavior. |
| `reactor.dubbo.providers` | empty | Static provider list in `host:port,host2:port` format. When set, `NativeDubboConsumerClient` skips Java ZooKeeper discovery. | Set this in Kubernetes/service-DNS/sidecar mode. This is the simplest low-RSS path. |
| `reactor.dubbo.registry-address` | `zookeeper://127.0.0.1:2181` | ZooKeeper address used only when `providers` is empty. | Change it only if you want ZooKeeper discovery. |
| `reactor.dubbo.registry-root` | `dubbo` | Root path for provider nodes in ZooKeeper. | Change it if your Dubbo providers are registered under a custom root. |
| `reactor.dubbo.registry-check` | `false` | Controls whether registry availability should be treated as startup-critical in discovery mode. | Keep `false` for rolling deployments. Use readiness checks if startup must fail when registry is missing. |

### Call Behavior

| Property | Default | What It Does | When To Change It |
| --- | ---: | --- | --- |
| `reactor.dubbo.timeout-ms` | `1000` | Per-call timeout. It bounds how long the consumer waits for a provider response. | Lower it for strict p99. Raise it only if the provider normally needs more time. |
| `reactor.dubbo.retries` | `0` | Number of extra attempts after a failed call. | Keep `0` unless the provider method is idempotent. Retries can multiply load and increase p99. |
| `reactor.dubbo.check` | `false` | Controls whether a reference should require provider availability during startup. | Keep `false` for rolling provider restarts. Add application readiness checks if the dependency is mandatory. |
| `reactor.dubbo.lazy` | `false` | Indicates lazy reference behavior for official-style compatibility. Native static mode still creates the native client when the reference is used. | Use only if you need delayed reference behavior. |
| `reactor.dubbo.protocol` | `dubbo` | Protocol name expected by classic Dubbo providers. | Usually do not change. |
| `reactor.dubbo.serialization` | `hessian2` | Serialization name used by classic Dubbo. | Keep `hessian2` for current native mode. |
| `reactor.dubbo.cluster` | `failfast` | Supported values are `failfast` and `failover`. | Use `failfast` for predictable latency. Use `failover` only for idempotent calls. |
| `reactor.dubbo.loadbalance` | `random` | Supported values are `random` and `roundrobin`. | Use `random` by default. Use `roundrobin` when you want simple even distribution. |

### Native Resource Limits

| Property | Default | What It Does | When To Change It |
| --- | ---: | --- | --- |
| `reactor.dubbo.max-inflight` | `32` | Max concurrent native Dubbo calls per reference. This is a bulkhead. | Lower it for low RSS and fail-fast behavior. Raise it only with enough provider capacity. |
| `reactor.dubbo.max-response-bytes` | `8388608` | Max Dubbo response frame size accepted by native mode. | Raise it only if your provider really returns larger payloads. Prefer streaming or smaller responses when possible. |
| `reactor.dubbo.native-connections-per-endpoint` | `1` | Max keepalive TCP connections per provider endpoint. One connection carries one in-flight call at a time. | Use `2` for a measured two-lane DB write path. Balanced throughput may use more only after a provider-capacity gate. |
| `reactor.dubbo.native-max-idle-connections-per-endpoint` | `1` | Max completed connections retained for reuse per endpoint. | Keep it at or below `native-connections-per-endpoint`. Lower it when idle RSS matters. |
| `reactor.dubbo.native-idle-connection-ttl-ms` | `30000` | Drops an idle pooled connection before reuse after this age. Older idle sockets are reconnected before request bytes are sent. | Keep `30000` unless the provider closes idle connections sooner. Then choose a smaller value than the provider idle timeout. Valid range: `1000..3600000`. |
| `reactor.dubbo.native-async-workers` | `1` | Native worker count used for async Dubbo calls. | Low RSS can keep this small. Raise it with the connection count only after load tests. |
| `reactor.dubbo.native-async-queue-capacity` | `32` | Bounded queue for native async calls. If full, calls fail fast instead of growing memory. | Keep bounded. Raise together with workers and route-level limits. |
| `reactor.dubbo.native-async-transport` | `blocking` | Async execution model. `blocking` uses the smallest worker model; `tokio-demux` uses Rust async request-id demux over provider connections. | Use `blocking` for lowest RSS and low traffic. Use `tokio-demux` for read-heavy/high-concurrency routes after a Docker RSS + p99 gate. |
| `reactor.dubbo.native-thread-stack-bytes` | `262144` | Stack size for native Dubbo worker and Tokio threads. Valid range: `131072..8388608`. | Keep `262144` for memory-first profiles. Raise only after a stack-overflow proof; lowering requires full route smoke tests. |

Transport selection is exclusive per native client. `blocking` allocates blocking endpoint pools and
no async endpoint pools. `tokio-demux` allocates async endpoint pools and no blocking endpoint pools;
the synchronous Java facade bridges to that same Tokio runtime. Verify this with
`nativeDubboBlockingEndpointPools` and `nativeDubboAsyncEndpointPools` metrics. Do not enable both
resource models to hide overload.

Blocking transport validates a connection that has been idle for at least 100 ms before reuse. A
closed provider socket is discarded before the request starts. The client never blindly retries a
write after request bytes may have been sent. Watch `nativeDubboIdleConnectionsExpired`,
`nativeDubboIdleConnectionValidations`, `nativeDubboStaleIdleConnectionsDiscarded`, and
`nativeDubboStaleConnectionRetries` when testing provider restarts.

### ZooKeeper And Official-Mode Properties

| Property | Default | What It Does | When To Change It |
| --- | ---: | --- | --- |
| `reactor.dubbo.registry-timeout-ms` | `3000` | Timeout for ZooKeeper connection/operations. | Change only in ZooKeeper discovery mode. |
| `reactor.dubbo.registry-session-timeout-ms` | `30000` | ZooKeeper session timeout. | Change only if your registry environment requires it. |
| `reactor.dubbo.connections` | `1` | Official-mode connection setting. Native mode uses `native-connections-per-endpoint` instead. | Mostly relevant for official mode. |
| `reactor.dubbo.share-connections` | `1` | Official-mode shared connection setting. | Mostly relevant for official mode. |
| `reactor.dubbo.refer-thread-num` | `1` | Worker count for provider refresh in ZooKeeper mode. It is not a request worker pool. | Keep small unless you have many references refreshing at once. |
| `reactor.dubbo.runtime-profile` | `micro-dubbo` | Descriptive profile value. Host applications may map it to runtime presets. | Use `micro-dubbo` or `balanced-dubbo` according to your measured deployment profile. |

## Suggested Starting Profiles

Micro Dubbo / lowest RSS:

```properties
reactor.dubbo.runtime-profile=micro-dubbo
reactor.dubbo.transport=native
reactor.dubbo.providers=provider-1:20880
reactor.dubbo.retries=0
reactor.dubbo.timeout-ms=800
reactor.dubbo.max-inflight=32
reactor.dubbo.native-connections-per-endpoint=1
reactor.dubbo.native-idle-connection-ttl-ms=30000
reactor.dubbo.native-async-workers=1
reactor.dubbo.native-async-queue-capacity=32
```

Balanced:

```properties
reactor.dubbo.runtime-profile=balanced-dubbo
reactor.dubbo.transport=native
reactor.dubbo.providers=provider-1:20880,provider-2:20880
reactor.dubbo.retries=0
reactor.dubbo.timeout-ms=1200
reactor.dubbo.max-inflight=512
reactor.dubbo.native-connections-per-endpoint=16
reactor.dubbo.native-async-workers=8
reactor.dubbo.native-async-queue-capacity=1024
```

Use micro Dubbo when memory is the first priority and overload can return controlled 503 responses.

Use balanced when Dubbo throughput matters more and you have enough provider capacity.

## Native Mode vs Official Mode

Native mode is the default because it keeps the hot JVM smaller. It avoids loading the official Dubbo `ReferenceConfig`, registry directory, official remoting, Netty, Java ZooKeeper client, Hessian Lite on the hot no-arg `byte[]` path, config-center, metadata-center, tracing, metrics modules, and extra transport codecs.

Official mode is still useful when correctness depends on full Dubbo behavior. Use `DubboConsumers`/`DubboConsumerClient` only for this compatibility path. If you use official mode, add the required optional dependencies explicitly and expect higher RSS.

## Current Native-Mode Limits

Native mode is intentionally focused. It does not currently implement:

- Dubbo config-center and metadata-center.
- Consumer registration under `/consumers`.
- Official Dubbo routers and governance rules.
- Triple, REST protocol, HTTP/2, callbacks, generic invocation, or full official filter-chain behavior.
- Multiplexed request demux over a single Dubbo connection.

For many REST-to-Dubbo consumer cases this is enough. If your system depends on the missing features, use official mode or put a full Dubbo integration service behind a smaller REST process.

## Production Checklist

- Prefer generated clients; keep manual invoker wiring for deliberately unsupported contracts.
- Use static Kubernetes Service DNS unless registry semantics are a real requirement.
- Keep retries at `0` for commands unless the operation is explicitly idempotent.
- Bound timeout, max in-flight calls, queue capacity, connections, and route admission together.
- Return native handles only when Java does not need to inspect or transform the body.
- Expose dependency readiness separately from process liveness.
- Test provider restart, endpoint removal, c64/c256 load, p99, `503`, RSS, and post-idle retention.
- Use official mode when correctness requires an unsupported governance feature; do not emulate it
  partially in business code.

## Glossary

| Term | Meaning |
| --- | --- |
| Discovery | How the consumer receives provider addresses |
| Static discovery | A configured address or Service DNS; it may still represent many replicas |
| Native mode | Rust transport with the deliberately small supported Dubbo feature set |
| Official mode | Apache Dubbo client runtime with its broader compatibility and larger JVM surface |
| Demux | Matching an asynchronous response to its request id |
| Bulkhead | A concurrency boundary that stops one dependency from consuming all capacity |
| Native response handle | A response id that lets HTTP send a Rust-resident provider body without a Java `byte[]` |

## Build

```powershell
mvn clean verify
```

Release artifacts are produced under `target/`:

- `java-rust-dubbo-0.7.3.jar`
- `java-rust-dubbo-0.7.3-native-static.jar`
- `java-rust-dubbo-0.7.3-codegen.jar` (build time only)
- `java-rust-dubbo-0.7.3-sources.jar`

## Documentation

- [Production Guide](docs/PRODUCTION_GUIDE.md)
- [Release Notes](docs/RELEASE_NOTES_v0.7.3.md)
- [Turkish README](README.tr.md)
