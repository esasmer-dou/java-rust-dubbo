# java-rust-dubbo 0.7.4

[English](RELEASE_NOTES_v0.7.4.md) | [Turkish](RELEASE_NOTES_v0.7.4.tr.md)

Opsiyonel REST bağımlılığı ve yayın build'i REST 4.6.3 ile hizalanır. Dubbo ABI 7 korunur. Java interface, generated client, Hessian davranışı ve dependency profilleri değişmez. Bu paket REST companion kütüphanesidir; bağımsız Spring Dubbo 0.7.0 ürünü değildir.

## Güncelleme

Maven bağımlılığını veya parent sürümünü güncelleyin. Uygulamayı yeniden derleyin. Annotation processor sürümü platformla hizalı olsun. İşletim sistemi ve işlemcinize uygun paket içi native dosyayı kullanın. Eski sürümün dosyalarını yerinde değiştirmeyin.

İş mantığı Java'da kalır. Runtime reflection veya yeni bir Java I/O katmanı eklenmez. Telemetri kendiliğinden açılmaz. ARM64 native dosyaları CI üzerinde derlenir. Bu sürümün uygulama kontrolleri x64 üzerinde yapılır. Bu patch için yeni ve evrensel bir RSS veya throughput kazanımı iddia edilmez.

GitHub Packages için Maven `settings.xml` içinde geçerli `read:packages` token gerekir. Repo public olsa da bu kural geçerlidir. Git SSH erişimi Maven kimlik doğrulamasından ayrıdır.

