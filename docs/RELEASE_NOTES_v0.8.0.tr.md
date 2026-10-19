# java-rust-dubbo 0.8.0

Rust-Java REST/platform 4.7.0 ile kullanın. Dubbo ABI 7 olarak kalır. Java servis ve Dubbo iletişim sözleşmeleri değişmez.

Native async callback'ler mevcut sınırlı worker bağlantılarını kullanır. Her callback kendi JNI yerel referans alanını taşır. Bu alan işlem sonunda bırakılır. Blocking ve Tokio-demux yollarında timeout ve hata yönetimi korunur. Worker veya kuyruk eklenmez.

Provider'ın düşük bellek ayarları Netty buffer parçasını küçültür. Thread başına buffer tutulmasını kapatır. Kendi açık ayarlarınız korunur. Bu ayarları Netty başlamadan uygulayın. Ayar değişikliği DB kapasitesini artırmaz.

DB uygulamasına `rust-java-jdbc:4.7.0` bağımlılığını açıkça ekleyin. Transaction içinde verilen session'ı kullanın. Sıradan consumer bu isteğe bağlı modülü almaz. PostgreSQL idempotency kullanımı açıkça seçilir. Otomatik yazma tekrarı eklenmez.

REST 4.7.0 ile eşleşen temiz kaynak manifestli native dosyaları kullanın. Annotation'lar, üretilen client'lar, static/ZooKeeper keşfi ve yanıt tipleri kullanılmaya devam eder.

[Örnekler ve ayarlar](functional-operations.tr.md) | [English](RELEASE_NOTES_v0.8.0.md)
