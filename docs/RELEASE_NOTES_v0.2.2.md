# java-rust-dubbo 0.2.2

`0.2.2` keeps the native Dubbo behavior unchanged and adds optional provider-side JDBC helpers for
plain Java Dubbo providers.

## What Changed For Users

- `com.reactor.rust.dubbo.provider.jdbc.JdbcRepository` is available for repeated JDBC
  connection/query/lifecycle code.
- `com.reactor.rust.dubbo.provider.jdbc.HikariDataSources` can create a Hikari pool from
  `sample.db.*`-style properties.
- Hikari is optional. Static/catalog-only providers do not need to load it.
- Provider SQL and row mapping stay explicit in application code.
- Existing consumer APIs and native response handle paths are unchanged.

## Provider Example

```java
public final class CustomerRepository extends JdbcRepository {
    private CustomerRepository(Properties properties) {
        super(HikariDataSources.create(properties, "sample.db"), true);
    }

    public List<Customer> findCustomers() {
        return query("Find customers", SQL, SqlBinder.none(), CustomerRepository::toCustomer);
    }
}
```

## Compatibility Notes

- Native ABI did not change.
- `0.2.1` applications can upgrade without changing Dubbo consumer call paths.
- Provider DB helpers are additive. They are not required for non-DB providers.
