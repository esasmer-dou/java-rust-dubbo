# Production Guide

This adapter is built for a narrow production profile: Java business logic, Rust HTTP I/O, and minimal Dubbo consumer overhead.

## Runtime Modes

| Mode | Use When | Cost |
| --- | --- | --- |
| Native static providers | Lowest RSS and predictable latency are the priority. Provider addresses come from DNS/config/sidecar. | No Java ZooKeeper watcher. No official Dubbo/Netty runtime in the hot JVM. |
| Native ZooKeeper discovery | Provider discovery must come from ZooKeeper. | Extra Java ZooKeeper classes and watcher threads. |
| Official Dubbo mode | Full Dubbo governance/compatibility is required. | Highest classpath/thread/RSS cost. |

## Recommended Profiles

Low RSS:

```properties
reactor.dubbo.transport=native
reactor.dubbo.providers=provider-1:20880
reactor.dubbo.retries=0
reactor.dubbo.timeout-ms=800
reactor.dubbo.max-inflight=64
reactor.dubbo.native-connections-per-endpoint=2
reactor.dubbo.native-max-idle-connections-per-endpoint=2
reactor.dubbo.native-idle-connection-ttl-ms=30000
reactor.dubbo.native-async-workers=2
reactor.dubbo.native-async-queue-capacity=64
```

Balanced Dubbo:

```properties
reactor.dubbo.transport=native
reactor.dubbo.providers=provider-1:20880,provider-2:20880
reactor.dubbo.retries=0
reactor.dubbo.timeout-ms=1200
reactor.dubbo.max-inflight=512
reactor.dubbo.native-connections-per-endpoint=16
reactor.dubbo.native-max-idle-connections-per-endpoint=4
reactor.dubbo.native-idle-connection-ttl-ms=30000
reactor.dubbo.native-async-workers=8
reactor.dubbo.native-async-queue-capacity=1024
```

Throughput:

```properties
reactor.dubbo.transport=native
reactor.dubbo.providers=provider-1:20880,provider-2:20880,provider-3:20880
reactor.dubbo.retries=0
reactor.dubbo.timeout-ms=2000
reactor.dubbo.max-inflight=1024
reactor.dubbo.native-connections-per-endpoint=32
reactor.dubbo.native-max-idle-connections-per-endpoint=8
reactor.dubbo.native-idle-connection-ttl-ms=30000
reactor.dubbo.native-async-workers=16
reactor.dubbo.native-async-queue-capacity=4096
```

## Provider Executor

Providers started through `DubboProviderApplication` use a bounded executor by default:

```properties
dubbo.provider.executor.thread-pool=eager
dubbo.provider.executor.core-threads=1
dubbo.provider.executor.max-threads=8
dubbo.provider.executor.queue-capacity=16
dubbo.provider.executor.idle-timeout-ms=30000
dubbo.provider.executor.io-threads=1
```

Keep DB service/method limits at or below Hikari capacity. The executor is shared by exported
interfaces on the same Dubbo port, so it can be wider than one DB service gate. Do not use an
unbounded handler queue or `cached` pool in a memory-limited pod.

## Backpressure Rules

- `max-inflight` is a per-reference RPC bulkhead.
- `native-async-queue-capacity` must be bounded. An unbounded queue is an RSS and p99 failure mode.
- Route-level bulkhead should be lower than the total native queue when the endpoint has strict latency SLOs.
- Prefer fail-fast 503 over letting thousands of requests wait behind slow provider calls.

## Timeout Rules

- Keep provider timeout lower than HTTP route timeout.
- Keep client/load-balancer timeout higher than route timeout.
- Do not enable retries for non-idempotent methods.
- If retries are enabled, account for retry amplification in `max-inflight`.

## Idle Connection And Write Safety

- Keep `reactor.dubbo.native-idle-connection-ttl-ms=30000` as the starting value.
- If the provider or load balancer closes idle TCP connections sooner, set the TTL below that timeout.
- Blocking transport probes connections that have been idle for at least 100 ms before writing.
- A closed idle socket is discarded and reconnected before request bytes are sent.
- The client does not blindly retry a command after partial request transmission. Write methods still need an idempotency key at the business layer.

## Provider Discovery

BEST for low RSS: static providers via config, DNS, or sidecar-generated list.

ZooKeeper mode is supported for dynamic discovery, but it is not the lowest-RSS mode because the JVM must keep a ZooKeeper client and watcher path alive.

## Observability

Expose and watch:

- Native Dubbo calls, errors, timeouts, rejected calls, and pool exhaustion.
- Native open/idle connections.
- Native expired idle connections, idle validations, stale idle discards, and safe pre-write retries.
- Native async submitted/completed/rejected.
- HTTP p95/p99 and 5xx counters.
- Route-level bulkhead limit, in-flight, rejected, and timed-out counters.
- RSS, JVM heap, direct buffer memory, thread count, and class count.

## Production Gate

Do not ship a service with this adapter until these are verified under the target container limits:

- Idle RSS with Dubbo enabled and disabled.
- Warm RSS after load and 30s idle.
- c64/c256/c512/c1000 load with repeat runs.
- Provider restart and reconnect.
- Provider slow response and timeout.
- Provider unavailable and fail-fast behavior.
- Shutdown path releases native clients and watcher threads.
