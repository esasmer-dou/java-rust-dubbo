# java-rust-dubbo 0.8.1

[English](RELEASE_NOTES_v0.8.1.md) | [Türkçe](RELEASE_NOTES_v0.8.1.tr.md)

Düşük-overhead plain provider, Apache Dubbo filtre zincirini bilinçli olarak atlar.
Bu nedenle yalnız SPI telemetri filtresi eklemek çağrıları gözlemlemez.

Provider gözlemi gerekiyorsa başlangıçta açık `ModuleContext.decorateDispatchers(ProviderDispatcherDecorator)`
noktasını kullanın. Servisleri tanımlamadan önce ayarlayın. Seçilen dispatcher bir kez sarılır.
Tüm filtreler açılmaz. Proxy veya executor eklenmez.
Gözlem seçilmezse mevcut dispatcher ve export yolu değişmez.

```java
context.decorateDispatchers(myExplicitDecorator);
FullProviderModule.INSTANCE.configure(context);
```

Sample provider 0.7.1, Glowroot 0.7.0 ile sınırlı sync/async gözlemini gösterir.
İş mantığı Java'da kalır. Argümanlar, sonuçlar, taşıma sınırları ve timeout davranışı değişmez.
Başlangıçta export veya gözlem kurulumu Error üretirse açılan kaynaklar da temizlenir.

REST 4.7.0 kullanın. Native Dubbo ABI 7 değişmez. Bu Java değişikliği için DLL/SO yeniden derlenmez.
Platform BOM 4.7.0 hâlâ Dubbo 0.8.0 seçer. Yeni gözlem noktasını kullanan provider'da 0.8.1 sürümünü açıkça belirtin.
Bu patch için RPS veya RSS iyileşmesi iddiası yoktur. 0.8.0 ölçümlerinde kabul edilen sapmalar korunur.
