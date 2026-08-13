# java-rust-dubbo 0.7.2

`0.7.2` aligns the Dubbo consumer library with `rust-java-rest:4.4.0`.

- Public consumer APIs, generated client declarations, discovery modes, provider restart handling,
  bulkheads, and Java business logic are unchanged.
- Native Dubbo timing aggregates can feed the optional bounded Glowroot micro telemetry plane.
- The shared runtime uses REST ABI `28`, Dubbo ABI `7`, Redis ABI `6`, and Glowroot ABI `1`.
- The native transport remains provided by the coordinated `rust-java-rest:4.4.0` artifact. The
  Dubbo package does not duplicate the DLL/SO.

Use the normal artifact when ZooKeeper, Java Hessian codecs, or official compatibility paths are
needed. Use the `native-static` classifier only for the documented static-provider native path.

