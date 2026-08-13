# java-rust-dubbo 0.7.2

`0.7.2`, Dubbo consumer kütüphanesini `rust-java-rest:4.4.0` ile hizalar.

- Public consumer API'leri, generated client tanımları, discovery modları, provider restart
  davranışı, bulkhead'ler ve Java business logic değişmez.
- Native Dubbo süreleri, isteğe bağlı Glowroot mikro telemetry katmanına aktarılabilir.
- Ortak runtime REST ABI `28`, Dubbo ABI `7`, Redis ABI `6` ve Glowroot ABI `1` kullanır.
- Native transport, koordineli `rust-java-rest:4.4.0` artifact'i tarafından sağlanır. Dubbo paketi
  DLL/SO dosyasını tekrar paketlemez.

ZooKeeper, Java Hessian codec veya resmi compatibility yolları gerekiyorsa normal artifact'i
kullanın. `native-static` classifier'ını yalnız dokümante edilmiş static-provider native yolu için
kullanın.
