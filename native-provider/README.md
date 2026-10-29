# Optional Native Provider

[English](README.md) | [Türkçe](README.tr.md) | [Project](../README.md)

An explicit alternative to `PlainDubboProvider` for synchronous, closed-schema
Java services. Generated Java invokes your business code; the shared ABI-3
JNI/Tokio engine handles classic Dubbo TCP and Hessian framing. There is no
second Dubbo acceptor in `rust-spring`, avoiding duplicate transport, deadline
and native ownership implementations. Existing provider defaults are unchanged.

**Source-only, unreleased modules.** The `0.9.0` coordinates below identify this
source build, not published packages. The runtime is `java-rust-dubbo-runtime:0.7.1`,
but current bounded/parser-policy native capabilities are also required. Current
frozen-source Linux functional checks and the focused warmed-load comparison
passed in EmaTech. Windows rebuild and coordinated distribution remain open.

## Start With The Sample

For runnable official/native profiles using the same Java service, see the
[sample guide](../../rest-sample-dubbo-provider/docs/NATIVE_PROVIDER.md). It includes
ports, configuration, shaded-JAR commands and typed 32/512-row HTTP use.

For another application, add the adapter and explicitly enable its processor:

```xml
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>java-rust-dubbo-native-provider</artifactId>
  <version>0.9.0</version>
</dependency>
```

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-compiler-plugin</artifactId>
  <version>3.13.0</version>
  <configuration>
    <release>21</release>
    <proc>full</proc>
    <annotationProcessorPaths>
      <path>
        <groupId>com.reactor</groupId>
        <artifactId>java-rust-dubbo-native-provider-codegen</artifactId>
        <version>0.9.0</version>
      </path>
    </annotationProcessorPaths>
    <annotationProcessors>
      <annotationProcessor>com.reactor.rust.dubbo.nativeprovider.codegen.NativeProviderProcessor</annotationProcessor>
    </annotationProcessors>
  </configuration>
</plugin>
```

Merge other required processors explicitly; do not put this processor on the
production classpath. The adapter's production dependencies are the original
`java-rust-dubbo:0.9.0` API (its transitives excluded) and shared runtime.
It needs neither Spring nor official Dubbo/Netty/HessianLite. The shared package
with the original API is supported on the classpath, not the JPMS module path.

Define a binding without changing the business interface:

```java
@GenerateNativeProvider(service = CustomerCommandService.class, version = "0.0.0")
final class CustomerDefinition {}
```

In your application startup, with an existing service implementation:

```java
var binding = CustomerDefinition__NativeProvider.bind(implementation,
    PlainDubboProvider.ServiceExecutionConfig.bounded(4,
        Map.of("createCustomerTyped", 2)));
var provider = NativeProviderFactory.start(
    NativeProviderFactory.Options.defaults(20880), List.of(binding), null);
// Keep the process alive and register provider.close() with its shutdown owner.
```

Types are in `com.reactor.rust.dubbo.provider`; the sample contract is
`com.reactor.rust.dubbo.sample.CustomerCommandService`, and `List`/`Map` are
from `java.util`. The optional third argument is a `ProviderDispatcherDecorator`,
applied once per service at startup. Bounded service/method gates run before
decorator/business invocation and release permits after business errors.
There is no reflective invocation, method-handle fallback, discovery or hidden
business retry in this adapter. Match the exact wire interface, group and version
with the consumer; use static endpoints or Kubernetes Service DNS.

Select the current SO explicitly with
`-Dreactor.dubbo.native.path=/opt/reactor/native/librust_dubbo.so`.
The factory checks wire/connection/response admission and parser-policy gauges
before method registration and bind. An ABI-3 library missing those capabilities
fails startup; the gauges alone do not attest a native build's provenance.
No platform artifact/classifier or native file is installed automatically.
Current Windows DLL/parser-policy and package rebuild remain item 17; an old
Windows or packaged library is not evidence for the verified Linux source.

## Supported Schema And Constructors

Supported synchronous contracts use primitives/boxed scalars, `String`,
`byte[]`, public enums, public nongeneric acyclic records, concrete `List<T>`
and `Map<String,V>`. Reference-valued data can be null. Record input decoding
calls the canonical constructor, including compact-constructor validation and
normalization; this is not constructor bypass.

| Bound | Limit |
| --- | ---: |
| Services / methods per service / methods total | 64 / 64 / 1024 |
| Method name / JVM descriptor / group or version | 128 / 2048 / 256 characters |
| Codec type depth / complex types | 16 / 128 |
| Record fields / enum constants | 64 / 256 |
| Input collection items | Configured limit, at most 4096 |
| Generated output collection items | 4096 |

Generic services/methods/records, overloads, futures/async completion, arbitrary
beans, raw/wildcard collections, dynamic `Object` and cyclic schemas are not
supported. Unknown/missing/duplicate record fields, null/duplicate map keys,
input collection aliases and unresolved references fail closed. Completed record
references are type-checked. Writers emit values, not preserved object identity;
coordinate schema evolution. This is not a general Jackson serializer.

Ordinary provider codecs allocate strings, arrays, records, argument arrays,
boxes and parser state. Negative floating-point zero is preserved by the adapter
helper. There is no blanket zero-allocation, zero-String or zero-copy guarantee.
Consumer direct-JSON projection is a separate capability: it can bypass DTO
constructors/validation and has its own schema/lifetime limits.

## Configuration And Admission

`Options` takes `port, ioWorkers, businessWorkers, queueCapacity,
maxPayloadBytes, requestTimeoutMs, drainTimeoutMs, maxCollectionItems`.

| Option | `Options.defaults(20880)` | Allowed |
| --- | ---: | --- |
| Port | 20880 | 1..65535 |
| IO workers | 1 | 1..8 |
| Business workers | 4 | 1..64 |
| Queue capacity | 64 | 1..1024 |
| Payload bytes | 262144 | 1024..1048576 |
| Request timeout / drain timeout | 5000 / 5000 ms | 1..60000 / 0..60000 ms |
| Input collection items | 1024 | 1..4096 |

`(businessWorkers + queueCapacity) * maxPayloadBytes <= 32 MiB`.
The API defaults imply 17 MiB logical admitted-request capacity; the sample uses
queue 16 and implies 5 MiB. Neither number is an allocated slab or total RSS.

| Native resource | Bound / lifetime |
| --- | --- |
| Connections | `min(businessWorkers + queueCapacity, 128)` per listener; 256 process-wide |
| Response admission | `min(businessWorkers + queueCapacity, 256)` per listener; 256 process-wide |
| Output registry | 256 fixed slots; 8 index bits and 55 generation bits in positive handles |
| Wire bytes | 32 MiB process-wide, covering retained requests, responses and error frames |
| Java codec contexts | Fixed slots equal to business workers; bounded CAS borrow, detach in `finally` |

Connection credit follows both socket halves and pending writers. Response credit
survives until callback and outbound-body owners finish. Wire credit is acquired
before declared body allocation; growth charges old and replacement allocations
together. These budgets exclude decoded Java/Rust objects, task metadata and
other process memory. Direct shared-engine users keep their ordinary 8 MiB
payload default; engine limits outside 1..33553408 bytes are rejected at startup,
independently of this adapter's stricter 1 MiB ceiling.

Provider reader policy 1 bounds invocation depth to 64, work to 65536 units,
class/type tables to 128 entries, fields/class to 64, each metadata name to
4096 bytes and charged metadata to 64 KiB. Materialized attachment values/owned
names are charged; skipped argument graphs are not materialized. Descriptors
are checked for at most 64 array dimensions and 256 parameters before recursive
parsing. These are parser budgets, not a total-RSS or all-inputs security claim.

## Lifetime And Remaining Locks

Use a dedicated process and exclusive registration owner. Native method
registration has no unregister: after a native start attempt, including a failed
one, restart with a new JVM. Do not mix this factory with a direct runtime
provider in the same process. The listener binds all interfaces; restrict access
to the owned environment.

Output storage and pending completion ownership share the 256 generation-tagged
atomic slots; neither uses a separate mutex-protected map. Completion is sent
after releasing exclusive slot access, and pending gauges read atomic flags.
Stale handles cannot modify a newer generation, and exhausted generations retire
rather than wrap. Slot contention fails fast; there is no unbounded registry
retry loop. Each handle has one Java writer, which must discard old views after
growth and complete only after its final write.

Timeout/drain cancels the network wait, not an active Java memory borrow.
Borrowed storage/admission stay alive until the real callback completion;
outbound response storage keeps credit through socket write/drop. Close rejects
new dispatch and requests bounded drain, is idempotent, and never forcibly stops
Java business code. Daemon-attached business workers do not pin natural JVM exit.
Give Kubernetes termination grace more time than drain plus a safety margin.

**The engine is not wholly lock-free or wait-free.** The provider-registry mutex
is used for startup, stop and metrics, not per RPC. Per-connection writes retain
a Tokio async mutex: waiting does not block an OS thread, but it serializes
complete frames and a slow write can delay later responses on that connection.
The existing consumer-response registry is separate and is not on this provider
path. Bounded ownership and allocation costs remain; removing the output and
pending-map mutexes is not an end-to-end latency or memory guarantee.

## Metrics And Verification

Read `NativeDubboBridge.metricsJson()` from
`com.reactor.rust.dubbo.runtime`; do not expose diagnostics publicly.

| Metrics | Meaning |
| --- | --- |
| `providerWireBytesUsed/Limit`, `providerWireRejections` | Wire-byte ownership and rejection |
| `providerConnectionLimit`, `providerConnectionAdmissionUsed/Limit/Rejections` | Listener totals and process-wide connection admission |
| `providerResponseLimit`, `providerResponseAdmissionUsed/Limit/Rejections` | Listener totals and process-wide response admission |
| `providerPendingResponses`, `providerOutputHandles`, `providerOutputBytes` | Pending completion and owned output storage |
| `providerReaderPolicyVersion`, `providerReaderDepthLimit`, `providerReaderWorkLimit`, `providerReaderMetadataBytesLimit` | Required parser-policy capabilities |

Slash-separated names above denote separate JSON fields with those suffixes.
Concurrent pending/output gauges are approximate. Late callbacks/outbound bodies can
legitimately keep ownership after timeout; investigate the owner before calling
it a leak. After actual completion/drain, check ownership returns to idle.

Build-only, from workspace root after preparing coordinated dependencies:

```bash
mvn -B -ntp -f java-rust-dubbo/native-provider-build/pom.xml -Dmaven.test.skip=true install
```

Execute tests in an isolated, bounded test environment with the current verified
native library. Our recorded verification used EmaTech:

```bash
NATIVE_LIB=/opt/reactor/native/librust_dubbo.so
mvn -B -ntp -f java-rust-dubbo/native-provider-build/pom.xml \
  "-DtrackA.native.path=$NATIVE_LIB" \
  "-DtrackA.proof.dir=$PWD/native-provider-proof" test
```

The integration test supports Linux `java` and Windows `java.exe`, but current
Windows artifact verification remains open. Missing native input/capabilities
fail rather than silently skip. Child class-load checks require generated/JNI
classes and reject official Dubbo, Netty, Spring and method-handle fallback.
The framing helper is test-only, not another production transport.

On 27 October 2026, the current frozen source passed EmaTech functional checks:
74 Rust tests (57 native + 17 core) and 171 Java tests (164 + 7 sample), including
bounded metadata-plan bootstrap and response/completion ownership. Two
unsupported OpenJ9 allocation checks were aborted, not counted as successes;
eight separate calibrated HotSpot allocation checks passed. Linux build, fmt and
Clippy checks passed; maximum GLIBC symbol remains 2.14.

The separate four-pair warmed-load comparison passed all nine cells at 1 CPU /
128 MiB per application. The 32/512-row targets increased useful RPS by 15.70–21.43%,
reduced p99 by 4.27–17.63% and reduced provider RSS by 19.90–27.10 MiB versus the
official engine. All three unchanged health-control cells stayed within their
bands. Final heap decreased by a paired median 7.04 MiB.

The native engine reuses one immutable protocol header plan (at most 1 KiB per
connection) learned only from scalar wire arguments. Every argument is still
validated; changed metadata and graphs use the full bounded parser. Responses
are never cached. Java business code and public contracts remain unchanged.

This is focused warmed-load acceptance, not cold-start/idle-burst or combined
product qualification. See the [sample guide](../../rest-sample-dubbo-provider/docs/NATIVE_PROVIDER.md)
for the full comparison and profile settings. Windows DLL/parser-policy and
coordinated package/distribution rebuild remain item 17.
