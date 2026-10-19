# java-rust-dubbo 0.8.0

Use with Rust-Java REST/platform 4.7.0. Dubbo ABI stays 7; the Java service and Dubbo wire contracts stay unchanged.

Native async callbacks reuse existing bounded worker attachments. Each callback owns a JNI local-reference frame, released after completion. Both blocking and Tokio-demux transports retain timeout/error handling without adding workers or queues.

Provider low-RSS tuning limits pooled Netty chunk size and disables per-thread buffer retention. Explicit user overrides remain supported. Apply tuning before Netty starts; it does not raise database capacity.

Database applications explicitly add `rust-java-jdbc:4.7.0` and use the supplied transaction session. Ordinary consumers do not inherit that optional module. PostgreSQL idempotency is explicit and does not add automatic write retries.

Use matching clean-provenance native files from REST 4.7.0. Existing annotations, generated clients, static/ZooKeeper discovery and response types remain available.

[Examples and settings](functional-operations.md) | [Türkçe](RELEASE_NOTES_v0.8.0.tr.md)
