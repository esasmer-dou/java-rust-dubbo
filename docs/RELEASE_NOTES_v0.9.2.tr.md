# 0.9.2: Tamamlama Kuyruğunda İzin Yönetimi

[English](RELEASE_NOTES_v0.9.2.md) | [Turkish](RELEASE_NOTES_v0.9.2.tr.md)

`com.reactor:java-rust-dubbo:0.9.2` bağımlılığını REST/platform `4.8.2` ile kullanın.
Java iş arayüzleri, provider seçimi ve kabul sınırları değişmedi. Dubbo ABI 7
olarak kalır. Ayrı Spring Dubbo ürününün ABI değeri de 3 olarak korunur.

Ortak Rust runtime, sonucu kuyruktan aldıktan sonra Java'yı çağırmadan önce
kuyruk iznini bırakır. Yanıt belleği izni callback bitene kadar korunur.
Callback yeni RPC başlattığında, kendi önceki sonucunun kuyruk izni yüzünden
reddedilmez. Kuyruk ve gövde belleği sınırları uygulanmaya devam eder. Ret
mesajları tamamlama kuyruğunu, async worker'ları ve endpoint istek kuyruğunu ayırır.

Native dosya yolunu elle veriyorsanız JAR ile birlikte REST native dosyasını da
güncelleyin. Yalnız Java bağımlılığını değiştirmek o dosyayı yenilemez. Callback
içinde uzun veya bloklayan iş yapmayın. Böyle işler için belgelenmiş açık
executor yolunu kullanın. Ortak kuyruğu büyütmek bu sorunu çözmez.

Varsayılan consumer'a Apache Dubbo/Netty runtime eklenmez. Deneysel native
provider modülleri ayrı ve isteğe bağlı kalır. Arayüz ve metot sınırları korunur.
