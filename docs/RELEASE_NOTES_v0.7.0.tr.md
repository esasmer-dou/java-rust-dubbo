# java-rust-dubbo 0.7.0

`0.7.0`, native Dubbo consumer'ları `rust-java-rest:4.2.0` ile hizalar. Opsiyonel client yüzeyleri
build time sırasında deklaratif olarak seçilebilir.

## Neler Eklendi?

- `@EnableNativeDubboClients(staticOnly = true)`, yanlışlıkla ZooKeeper discovery ayarı verilirse
  startup'ı durdurur.
- `@GenerateNativeDubboClient`; `enabledProperty`, `havingValue` ve `matchIfMissing` ile generated
  client bean'ini koşullu olarak açabilir.
- Generated uygulamanın ihtiyaç duyduğu runtime annotation'ları normal ve native-static JAR'larda
  kalır.
- Processor implementation sınıfları ve service metadata yalnız build-only `codegen` JAR'ında
  bulunur.
- Generated client'lar request-time proxy dispatch kullanmadan tek bounded native transport
  lifecycle'ını paylaşır.

## Uyumlu Dependency

```xml
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>java-rust-dubbo</artifactId>
  <version>0.7.0</version>
</dependency>
```

`rust-java-rest:4.2.0` kullanın. Native Dubbo ABI `7` olarak kalır. Ortak runtime REST ABI `26` ve
Redis ABI `6` taşır.

## Uyumluluk

Provider kontratları, generated client metot imzaları, static/ZooKeeper discovery property'leri ve
Java service orchestration korunur. Yeni conditional property açıkça verilmezse mevcut client
tanımları çalışmaya devam eder.
