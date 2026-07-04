# java-rust-dubbo 0.2.3

`0.2.3` keeps the native Dubbo data-plane behavior unchanged and reduces provider-side
configuration noise.

## What's New

- `HikariDataSources` now has low-RSS defaults for optional pool tuning keys.
- Plain provider samples can keep only JDBC URL, driver, username and password in the minimum
  property file.
- Advanced provider pool and Netty tuning can live in a separate overlay file.

## Compatibility

- Native ABI did not change.
- Consumer API did not change.
- Existing `0.2.2` provider applications can keep their explicit Hikari properties. They are still
  honored and override the built-in defaults.
