# java-rust-dubbo 0.7.4

[English](RELEASE_NOTES_v0.7.4.md) | [Turkish](RELEASE_NOTES_v0.7.4.tr.md)

Aligns the optional REST dependency and release build to REST 4.6.3. Dubbo ABI remains 7. Java interfaces, generated clients, Hessian behavior and dependency profiles are unchanged. This is the REST companion library, not the standalone Spring Dubbo 0.7.0 product.

## Upgrade

Update the Maven dependency or parent and rebuild the application. Keep annotation processors on the aligned platform version. Use the packaged native library for your OS and CPU. Do not replace an old release's assets in place.

Java business logic stays in Java. No runtime reflection, new Java I/O stack or default telemetry activation is added. ARM64 binaries are cross/native-built in CI; runtime application checks for this release use x64. This patch does not claim a new universal RSS or throughput result.

GitHub Packages requires a valid `read:packages` token in Maven `settings.xml`, even for a public repository. Git SSH access is separate from Maven authentication.

