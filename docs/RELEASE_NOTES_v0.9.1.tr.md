# 0.9.1 — Uyumlu Runtime Paketleri

[English](RELEASE_NOTES_v0.9.1.md) | [Türkçe](RELEASE_NOTES_v0.9.1.tr.md)

Yayım tarihi: 30 Ekim 2026.

`java-rust-dubbo:0.9.1` ile REST/platform `4.8.1` kullanın. Cache varsa `0.9.1`
seçin. Bu düzeltme REST bağımlılığını ve native kaynak kontrolünü hizalar.
Yeni RPC havuzu oluşturmaz. Çağrı API'leri değişmez. Native dosyalar yeniden
derlenmez. Dubbo ABI `7` olarak kalır. İş kuralları ve tipli interface'ler Java'dadır.

Spring Boot için üretilmiş consumer kullanıyorsanız ayrı Spring Dubbo `0.8.1`
modüllerini seçin. Bu modüller, consumer metoduna ve referansına zaman aşımı
tanımlamayı sağlar. Provider çalışma süresi sınırı ayrı bir ayardır.

Uygulamayı hizalı bağımlılıklarla yeniden derleyin. Paketlerin içindeki native
dosyaları birlikte kullanın. Başka sürümden DLL/SO kopyalamayın. Linux x64 işlev
testleri doğrulanmıştır. ARM derleme ve kaynak kanıtı, ARM çalışma testi değildir.
Bu düzeltme yeni RSS, RPS veya p99 kazanımı iddiası taşımaz.

Ayrı native-provider modülleri deneysel kalır. Mevcut ısınma iyileştirme kaydı
bu paket sürümüyle kapatılmış sayılmaz.
