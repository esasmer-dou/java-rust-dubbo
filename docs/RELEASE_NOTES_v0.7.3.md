# java-rust-dubbo 0.7.3

[English](RELEASE_NOTES_v0.7.3.md) | [Turkish](RELEASE_NOTES_v0.7.3.tr.md)

`0.7.3` aligns the lightweight Dubbo consumer library with `rust-java-rest:4.5.5`.

## What Users Get

- The optional REST dependency and release gate now target `4.5.5`.
- REST ABI `29` and Dubbo ABI `7` remain unchanged.
- Generated clients, static and ZooKeeper discovery, native response handles, bounded transport,
  and the Java business programming model are unchanged.
- No official Dubbo, Netty, ZooKeeper, or Hessian dependency is added to the `native-static`
  classifier.

Use the native runtime packaged by the coordinated Rust-Java REST release.
