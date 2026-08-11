# java-rust-dubbo 0.7.1

`0.7.1`, native Dubbo consumer paketini `rust-java-rest:4.3.0` ile hizalar.

## Kullanıcıya Etkisi

- Consumer interface'leri, generated stub'lar, static discovery, ZooKeeper discovery, bulkhead,
  timeout, Hessian ve native response handle kullanımı değişmedi.
- Java handler ve business service kodu Java'da kalır.
- REST ABI `26`, Dubbo ABI `7` ve Redis ABI `6` olarak kalır.
- Uyumlu `rust-java-rest:4.3.0` paketiyle gelen DLL/SO dosyasını kullanın.

```xml
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>java-rust-dubbo</artifactId>
  <version>0.7.1</version>
</dependency>
```

Bu sürüm bir dependency hizalama patch'idir. Runtime feature yüzeyini büyütmez.
