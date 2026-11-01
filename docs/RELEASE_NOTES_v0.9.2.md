# 0.9.2: Completion Queue Ownership

[English](RELEASE_NOTES_v0.9.2.md) | [Turkish](RELEASE_NOTES_v0.9.2.tr.md)

Use `com.reactor:java-rust-dubbo:0.9.2` with REST/platform `4.8.2`.
Java business interfaces, provider selection and all admission limits stay the
same. Dubbo ABI remains 7; Spring Dubbo's independent ABI remains 3.

The shared Rust runtime releases a completion queue slot when the result is
dequeued, before invoking Java. It retains the response-body permit throughout
the callback. A callback starting its next RPC no longer competes for the queue
slot of its own dequeued result. Queue capacity and bounded body memory remain
enforced. Rejection messages distinguish the completion queue, async workers
and endpoint request queue.

Install the coordinated REST native file as well as the JAR if your deployment
uses an explicit native path. A Java-only update does not replace that file.
Keep application callbacks short; use the documented explicit executor for
blocking business continuations rather than expanding a global queue.

This release does not change the default consumer into an Apache Dubbo/Netty
runtime. The experimental native-provider modules stay optional and separate.
Existing interface-level and method-level limits remain in force.
