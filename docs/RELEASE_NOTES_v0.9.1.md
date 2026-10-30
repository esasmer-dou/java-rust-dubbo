# 0.9.1 — Aligned Runtime Packaging

[English](RELEASE_NOTES_v0.9.1.md) | [Türkçe](RELEASE_NOTES_v0.9.1.tr.md)

Release date: October 30, 2026.

Use `java-rust-dubbo:0.9.1` with REST/platform `4.8.1` and cache `0.9.1` when used.
This patch aligns the REST dependency and native provenance contract. It does
not introduce another RPC pool, change invocation APIs or rebuild native files.
Dubbo ABI remains `7`; business logic and typed interfaces remain in Java.

For Spring Boot generated consumers, use the separate Spring Dubbo `0.8.1`
modules. They add per-method and per-reference consumer timeouts. Provider
execution deadlines remain a different setting.

Rebuild your application with the aligned artifacts. Keep the native resources
inside those artifacts; do not mix DLL/SO files from another release. Linux
x64 correctness is verified. ARM compile/provenance evidence is not an ARM
runtime qualification. This patch makes no new RSS, RPS or p99 improvement claim.

The separate native-provider modules remain experimental. The existing
`F18-WARMUP` improvement record is not closed by this packaging release.
