# java-rust-dubbo 0.7.3

[English](RELEASE_NOTES_v0.7.3.md) | [Türkçe](RELEASE_NOTES_v0.7.3.tr.md)

`0.7.3`, hafif Dubbo consumer kütüphanesini `rust-java-rest:4.5.5` ile hizalar.

## Kullanıcıya Gelen Değişiklikler

- İsteğe bağlı REST dependency ve release gate artık `4.5.5` sürümünü kullanır.
- REST ABI `29` ve Dubbo ABI `7` değişmedi.
- Generated client'lar, static ve ZooKeeper discovery, native response handle, sınırlı transport ve
  Java iş programlama modeli değişmedi.
- `native-static` classifier'a resmi Dubbo, Netty, ZooKeeper veya Hessian dependency eklenmedi.

Koordineli Rust-Java REST sürümünün paketlediği native runtime'ı kullanın.
