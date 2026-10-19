# Açık DB İşlemleri

[English](functional-operations.md) | [Turkish](functional-operations.tr.md)

Bu özellikler 0.8.0 paketindedir. Native ABI ve Dubbo sözleşmesi değişmez.

Repository içindeki `transactions()` metodunu servis kurulurken bir kez çağırın.
Bu çağrı bir işlem nesnesi oluşturur. DB bağlantısı açmaz.
`execute` içinde verilen `JdbcSession` nesnesini birlikte çalışan repository metotlarına geçirin.
Tek transaction içinde ikinci bir bağlantı açmayın.
Demo şeması constructor içinde değil, ilk işlemden önce hazırlanır.
Production trafiğini kabul etmeden önce bağımlılık readiness kontrolünü kullanın.

Yanıt kaybolduğunda aynı yazma işlemini güvenle tekrarlamak için
`PostgresIdempotency` kullanın. Sonuç ve DB değişikliği aynı transaction'da saklanır.
Anahtarın kapsamını doğrulanmış tenant, işlem ve şema sürümüyle belirleyin.
Aynı anahtarla farklı veri gelirse ikinci işlem yapılmaz. Conflict üretilir.

Bu özellik yalnız seçtiğiniz yazma işlemine ek DB maliyeti getirir.
Otomatik retry katmanı değildir.
DB kullanan uygulama `rust-java-jdbc` modülünü açıkça ekler.
Bu modülün üçüncü taraf runtime bağımlılığı yoktur.
Cache ve Dubbo kütüphanelerinde isteğe bağlıdır. Reader ve consumer'a otomatik taşınmaz.
[Ayrıntılı örnekler](https://github.com/esasmer-dou/rust-java-rest/blob/master/docs/explicit-operations.tr.md).
Provider sample'ında müşteri durum güncellemesi örneği bulunur.

## Native Cevaplar Ve Provider Buffer Sınırı

Native async cevaplar, mevcut worker'ın JVM bağlantısını tekrar kullanır.
Her cevap için ayrı bir JNI yerel referans alanı açılır. Cevap tamamlanınca bu alan temizlenir.
Worker kapanınca JVM bağlantısı da kapanır. Yeni worker veya kuyruk eklenmez.
Bu davranış `blocking` ve `tokio-demux` için geçerlidir. Java servis kullanımınız değişmez.

`DubboProviderRuntimeTuning.applyLowRssDefaults`, Netty buffer boyutlarını da sınırlar:

```properties
io.netty.allocator.maxOrder=3
io.netty.allocator.smallCacheSize=0
io.netty.allocator.normalCacheSize=0
io.netty.allocator.useCacheForAllThreads=false
```

Varsayılan 8 KiB sayfa boyutunda `maxOrder=3`, ayrılan bellek parçasını 4 MiB yerine 64 KiB yapar.
Bu değer toplam uygulama bellek limiti değildir. Daha büyük cevaplar da çalışır.
Sıfır cache boyutları, her executor thread'inin ayrıca buffer biriktirmesini önler.
JVM veya uygulama ayarlarıyla verdiğiniz değerler korunur. Bu ayarlar Netty başlamadan uygulanır.
Provider concurrency sınırını DB kapasitesine göre tutun. Bu ayarlar DB kapasitesini artırmaz.
