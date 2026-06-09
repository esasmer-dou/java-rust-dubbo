# java-rust-dubbo

`java-rust-dubbo`, Java/Rust REST framework içinde Dubbo provider çağırmak için hazırlanmış küçük ve kontrollü bir consumer kütüphanesidir.

Kullanım modeli basittir:

- REST handler, service ve component kodunuz Java'da kalır.
- HTTP server ve native I/O tarafı Rust Hyper ile devam eder.
- Java servisiniz Dubbo provider'a bu kütüphane üzerinden çağrı yapar.
- İsterseniz Dubbo TCP data-plane Rust tarafında çalışır; böylece consumer JVM daha küçük kalır.
- ZooKeeper, Netty ve resmi Dubbo client stack varsayılan olarak zorunlu değildir.

Bu kütüphane, "dependency ekleyince her şeyi otomatik yapsın" yaklaşımından bilinçli olarak uzak durur. Kurulum açık ve kontrollüdür. Bunun nedeni memory, thread ve latency davranışını üretim ortamında daha öngörülebilir yönetmektir.

## Ne Zaman Kullanılır?

Şu durumlarda uygundur:

- Java/Rust REST uygulamanızın Dubbo provider çağırması gerekiyorsa.
- Spring Boot starter veya full Dubbo client stack'i REST process içine almak istemiyorsanız.
- Kubernetes içinde provider adreslerini Service DNS, config veya sidecar-generated provider list ile verebiliyorsanız.
- Provider JSON veya binary body'yi hazır `byte[]` olarak döndürebiliyorsa.
- Düşük RSS, düşük allocation ve kontrollü backpressure önemliyse.

Şu durumlarda resmi Dubbo stack daha doğru olabilir:

- Dubbo config-center, metadata-center veya router/governance kurallarına ihtiyacınız varsa.
- Callback, generic invocation, Triple, REST protocol veya full filter-chain davranışı gerekiyorsa.
- Consumer registration ve tüm resmi Dubbo uyumluluğu zorunluysa.

## Maven

```xml
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>java-rust-dubbo</artifactId>
  <version>0.1.0-rc3</version>
</dependency>
```

Bu artifact Maven Central'da değil, GitHub Packages üzerinde yayınlanır. Repo veya package private ise sadece dependency eklemek yeterli değildir. Consumer projenin GitHub Packages Maven repository'sini tanımlaması ve GitHub token ile kimlik doğrulaması yapması gerekir.

Consumer projenin `pom.xml` dosyasına repository ekleyin:

```xml
<repositories>
  <repository>
    <id>github</id>
    <name>GitHub Packages</name>
    <url>https://maven.pkg.github.com/esasmer-dou/java-rust-dubbo</url>
  </repository>
</repositories>
```

Consumer makinede `~/.m2/settings.xml` içine Maven kimlik bilgisini ekleyin. Buradaki `<id>`, `pom.xml` içindeki repository id ile aynı olmalıdır:

```xml
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.0.0 https://maven.apache.org/xsd/settings-1.0.0.xsd">
  <servers>
    <server>
      <id>github</id>
      <username>YOUR_GITHUB_USERNAME</username>
      <password>${env.GITHUB_PACKAGES_TOKEN}</password>
    </server>
  </servers>
</settings>
```

Token classic GitHub PAT olmalı ve `read:packages` scope'u taşımalıdır. Package private ise token'ın ilgili private repo'ya erişimi de olmalıdır; pratikte çoğu kurulumda `repo` scope'u da gerekir. Organization SSO kullanıyorsa token organization için authorize edilmelidir. Token'ı `pom.xml` içine yazmayın.

Arkadaşınız repo'yu okuyabildiği halde Maven `401` veya `404` alıyorsa GitHub package yetkilerini kontrol edin. Package ya `esasmer-dou/java-rust-dubbo` reposundaki erişim ayarlarını miras almalı ya da arkadaşınıza veya takımına package üzerinde en az `Read` yetkisi verilmelidir.

Native modda bu dependency küçük tutulur. Uygulamanıza ZooKeeper, Netty, Hessian Lite veya resmi Dubbo client stack otomatik olarak taşınmaz.

En küçük static-provider native kurulum için full JAR yerine `native-static` classifier kullanabilirsiniz:

```xml
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>java-rust-dubbo</artifactId>
  <version>0.1.0-rc3</version>
  <classifier>native-static</classifier>
</dependency>
```

Bu classifier'ı sadece şu koşullarda kullanın:

- `reactor.dubbo.transport=native` kullanıyorsunuz.
- `reactor.dubbo.providers=host:port,...` dolu.
- Yoğun kullanılan Dubbo metodunuz no-arg `byte[]` dönen low-overhead path.
- Consumer tarafında Java ZooKeeper discovery, official Dubbo mode veya Java Hessian argument encode/decode gerekmiyor.

ZooKeeper discovery, argümanlı Dubbo metotları, DTO decode, official Dubbo uyumluluğu veya full governance gerekiyorsa classifier kullanmayın. Normal dependency bu compatibility path'leri korur.

Native modun çalışması için Java/Rust framework native library de yüklü olmalıdır. `rust-java-rest` içinde bu native library framework tarafından yüklenir. Standalone testlerde `rust_hyper` kütüphanesini `java.library.path` ile görünür hale getirmek gerekir.

## Kullanılacak API Sınırı

Uygulama kodunda sadece doğrudan `com.reactor.rust.dubbo` altındaki sınıfları kullanın. Örnek: `DubboConsumerConfig`, `DubboReferenceSpec`, `NativeDubboConsumerClient`, `NativeDubboConsumers` ve `NativeDubboMethodInvoker`.

`com.reactor.rust.dubbo.internal.*` altındaki sınıflar kütüphanenin iç uygulama detaylarıdır. Bu paketler sorumluluklarına göre ayrılmıştır:

- `internal.nativeclient`: native Dubbo transport reference, codec, descriptor ve native provider watcher.
- `internal.direct`: opsiyonel resmi Dubbo direct invoker yolu.
- `internal.registry`: ZooKeeper provider discovery ve Dubbo URL yardımcıları.
- `internal.runtime`: Dubbo runtime modeli ve low-RSS Netty tuning.
- `internal.util`: küçük runtime yardımcıları.

Servis kodunuzda `internal.*` import etmeyin. Bu paketler release candidate sürümleri arasında değişebilir. Source compatibility garantisi yalnızca public root API için verilir.

## Hızlı Başlangıç

Bu bölüm, bir Java/Rust REST uygulamasını adım adım Dubbo consumer haline getirir.

### 1. Bağımlılığı Ekleyin

Yukarıdaki Maven dependency'yi uygulamanızın `pom.xml` dosyasına ekleyin.

Private GitHub package'tan çekiyorsanız Maven bölümündeki GitHub Packages repository tanımını ve `~/.m2/settings.xml` kimlik bilgisini de ekleyin. Sadece dependency eklemek yeterli değildir, çünkü bu artifact Maven Central'da yoktur.

Dubbo Spring Boot starter eklemeniz gerekmez. Bu kütüphane framework içinde açık bean/config mantığıyla kullanılır.

### 2. Temel Property'leri Ekleyin

En düşük RSS için native transport ve static provider listesi ile başlayın:

```properties
reactor.dubbo.transport=native
reactor.dubbo.providers=catalog-provider:20880
reactor.dubbo.timeout-ms=1000
reactor.dubbo.retries=0
reactor.dubbo.max-inflight=256
reactor.dubbo.max-response-bytes=8388608
reactor.dubbo.native-connections-per-endpoint=16
reactor.dubbo.native-async-workers=8
reactor.dubbo.native-async-queue-capacity=1024
```

Bu ayarların anlamı:

- `transport=native`: Dubbo TCP çağrısını Rust native data-plane yapar.
- `providers=host:port`: Consumer hangi provider'a gideceğini bilir. Bu alan doluysa Java ZooKeeper client başlamaz.
- `retries=0`: Gizli retry yapılmaz. Latency daha öngörülebilir olur.
- `max-inflight`: Aynı anda kaç Dubbo çağrısının devam edebileceğini sınırlar.
- `native-async-queue-capacity`: Queue büyümesini sınırlar. Queue dolarsa kontrollü hata dönülür; memory şişirilmez.

### 3. Provider Interface'ini Tanımlayın

Provider hangi Java interface'i export ediyorsa consumer tarafında aynı interface adını ve metot imzasını kullanın.

Mevcut en hızlı native path için provider'ın no-arg `byte[]` dönmesi idealdir:

```java
package com.example.catalog;

public interface CatalogProviderApi {
    byte[] nestedCatalogJson();
}
```

Bu `byte[]` JSON olabilir. REST handler tarafında bunu doğrudan `RawResponse.json(...)` ile döndürebilirsiniz. Böylece büyük Java DTO graph oluşturmadan provider cevabını HTTP response'a taşırsınız.

### 4. Consumer Client'i Uygulama Başlangıcında Bir Kez Oluşturun

Dubbo client request başına oluşturulmaz. Uygulama başlangıcında bir kez oluşturulur ve tüm request'lerde tekrar kullanılır.

```java
import com.example.catalog.CatalogProviderApi;
import com.reactor.rust.di.annotation.Bean;
import com.reactor.rust.di.annotation.Configuration;
import com.reactor.rust.di.annotation.PreDestroy;
import com.reactor.rust.dubbo.DubboReferenceSpec;
import com.reactor.rust.dubbo.NativeDubboConsumerClient;
import com.reactor.rust.dubbo.NativeDubboConsumers;
import com.reactor.rust.dubbo.NativeDubboMethodInvoker;

@Configuration
public final class DubboClientConfiguration {
    private final NativeDubboConsumerClient client = NativeDubboConsumers.create();

    @Bean
    public CatalogClient catalogClient() {
        DubboReferenceSpec<CatalogProviderApi> spec = DubboReferenceSpec
                .builder(CatalogProviderApi.class)
                .timeoutMs(1000)
                .retries(0)
                .check(false)
                .lazy(true)
                .build();

        NativeDubboMethodInvoker<byte[]> invoker =
                client.method(spec, "nestedCatalogJson", byte[].class);

        return new CatalogClient(invoker);
    }

    @PreDestroy
    public void shutdown() {
        client.close();
    }
}
```

Dikkat edilmesi gerekenler:

- `NativeDubboConsumerClient` tek instance olarak yaşamalıdır.
- `NativeDubboMethodInvoker` startup'ta hazırlanır; request sırasında tekrar hesaplanmaz.
- `shutdown()` içinde `client.close()` çağrılmalıdır. Bu native client ve connection kaynaklarını temizler.

### 5. Java Client Wrapper Yazın

Handler veya business service içinde doğrudan Dubbo detaylarıyla çalışmamak daha temizdir. Küçük bir wrapper class yazın.

```java
import com.reactor.rust.dubbo.NativeDubboMethodInvoker;

import java.util.concurrent.CompletableFuture;

public final class CatalogClient {
    private final NativeDubboMethodInvoker<byte[]> nestedCatalogJson;

    public CatalogClient(NativeDubboMethodInvoker<byte[]> nestedCatalogJson) {
        this.nestedCatalogJson = nestedCatalogJson;
    }

    public byte[] nestedCatalogJson() {
        return nestedCatalogJson.invoke();
    }

    public CompletableFuture<byte[]> nestedCatalogJsonAsync() {
        return nestedCatalogJson.invokeAsync();
    }
}
```

`invoke()` senkron çağrıdır.

`invokeAsync()` async çağrıdır. Handler `CompletionStage` dönebiliyorsa bu yol daha doğrudur; request worker thread'i Dubbo cevabını bloklayarak beklemez.

### 6. Handler İçinden Kullanın

Provider JSON döndürüyorsa bunu raw JSON response olarak sunabilirsiniz:

```java
import com.reactor.rust.annotations.RustRoute;
import com.reactor.rust.di.annotation.Autowired;
import com.reactor.rust.http.RawResponse;
import com.reactor.rust.http.ResponseEntity;

import java.util.concurrent.CompletableFuture;

public final class CatalogHandler {
    @Autowired
    private CatalogClient catalogClient;

    @RustRoute(
            method = "GET",
            path = "/api/v1/catalog",
            responseType = RawResponse.class
    )
    public CompletableFuture<ResponseEntity<RawResponse>> catalog() {
        return catalogClient.nestedCatalogJsonAsync()
                .thenApply(json -> ResponseEntity.ok(RawResponse.json(json)));
    }
}
```

Provider hata verirse veya timeout olursa uygulama katmanında kontrollü HTTP response dönün. Örneğin 503 ve kısa bir JSON hata body çoğu senaryo için yeterlidir.

### 7. Route Seviyesinde Limit Ekleyin

Native client kendi içinde `max-inflight` ve queue limitleriyle korunur. Fakat HTTP endpoint seviyesinde de limit olması daha doğrudur.

Düşük RSS servislerde route bulkhead küçük tutulur. Overload durumunda kontrollü 503 dönmek, binlerce request'i queue'da bekletmekten daha sağlıklıdır.

### 8. Production Öncesi Test Edin

Canlıya çıkmadan önce şu senaryoları test edin:

- Dubbo kapalı idle RSS.
- Dubbo açık idle RSS.
- Load sonrası RSS ve 30 saniye idle sonrası RSS.
- c64, c256, c512, c1000 concurrency.
- Provider restart olduğunda consumer tekrar çağrı yapabiliyor mu?
- Provider yavaşladığında timeout çalışıyor mu?
- Queue dolduğunda sistem memory şişirmeden kontrollü hata dönüyor mu?
- Uygulama kapanırken native client release ediliyor mu?

## Property Referansı

Tüm ayarlar `reactor.dubbo.` prefix'i ile başlar.

Değerler üç yerden gelebilir:

- Java `Properties` objesi.
- Java system property.
- Environment variable.

System property ve environment variable, `Properties` objesindeki değerin üstüne yazar.

Environment variable yazarken büyük harf kullanın, `.` ve `-` karakterlerini `_` yapın:

```text
REACTOR_DUBBO_TIMEOUT_MS=1000
REACTOR_DUBBO_NATIVE_ASYNC_WORKERS=8
```

### Temel Ayarlar

| Property | Varsayılan | Ne İşe Yarar? | Ne Zaman Değiştirilir? |
| --- | ---: | --- | --- |
| `reactor.dubbo.application-name` | `rust-java-rest-dubbo-consumer` | Consumer adıdır. Log, diagnostic ve official mode metadata için anlamlıdır. | Servis adınızla değiştirin. Örnek: `orders-api`. |
| `reactor.dubbo.transport` | `native` | Dubbo çağrısını hangi transport'un yapacağını belirler. `native` Rust data-plane kullanır, `official` resmi Dubbo stack'i kullanır. | Düşük RSS için `native` kalsın. Full Dubbo özellikleri gerekiyorsa `official` kullanın. |
| `reactor.dubbo.providers` | boş | Static provider listesidir. Format: `host:port,host2:port`. Doluysa Java ZooKeeper başlamaz. | Kubernetes Service DNS, config veya sidecar provider list kullanıyorsanız doldurun. |
| `reactor.dubbo.registry-address` | `zookeeper://127.0.0.1:2181` | `providers` boş ise ZooKeeper adresidir. | Dynamic provider discovery gerekiyorsa ayarlayın. |
| `reactor.dubbo.registry-root` | `dubbo` | ZooKeeper içinde provider node'larının root path'idir. | Provider'lar farklı root altında register oluyorsa değiştirin. |
| `reactor.dubbo.registry-check` | `false` | Registry yoksa startup davranışını etkiler. | Rolling deploy için genelde `false` kalır. Dependency kritikse readiness check ekleyin. |

### Çağrı Davranışı

| Property | Varsayılan | Ne İşe Yarar? | Ne Zaman Değiştirilir? |
| --- | ---: | --- | --- |
| `reactor.dubbo.timeout-ms` | `1000` | Tek bir Dubbo çağrısının ne kadar bekleyeceğini belirler. | Provider normalde daha yavaşsa artırın. p99 önemliyse düşük tutun. |
| `reactor.dubbo.retries` | `0` | Başarısız çağrı sonrası kaç ek deneme yapılacağını belirler. | Sadece idempotent metotlarda artırın. Aksi halde duplicate iş ve p99 artışı yaratabilir. |
| `reactor.dubbo.check` | `false` | Reference startup'ta provider varlığını zorunlu görsün mü? | Rolling restartlarda `false` daha yumuşaktır. Dependency zorunluysa readiness ile kontrol edin. |
| `reactor.dubbo.lazy` | `false` | Reference'ın geç oluşturulması davranışıdır. | Startup'ta Dubbo hazırlığı istemiyorsanız kullanılabilir. |
| `reactor.dubbo.protocol` | `dubbo` | Classic Dubbo protocol adıdır. | Genelde değiştirilmez. |
| `reactor.dubbo.serialization` | `hessian2` | Dubbo serialization adıdır. | Native mode için `hessian2` kalsın. |
| `reactor.dubbo.cluster` | `failfast` | Desteklenen değerler: `failfast`, `failover`. | Latency için `failfast`. İdempotent çağrı ve provider alternatifi varsa `failover`. |
| `reactor.dubbo.loadbalance` | `random` | Desteklenen değerler: `random`, `roundrobin`. | Varsayılan `random` yeterlidir. Basit eşit dağılım için `roundrobin`. |

### Native Kaynak Limitleri

| Property | Varsayılan | Ne İşe Yarar? | Ne Zaman Değiştirilir? |
| --- | ---: | --- | --- |
| `reactor.dubbo.max-inflight` | `256` | Aynı anda devam eden native Dubbo çağrı limitidir. | Düşük RSS için azaltın. Provider kapasitesi yüksekse ve testlerle doğrulandıysa artırın. |
| `reactor.dubbo.max-response-bytes` | `8388608` | Native Dubbo response frame için maksimum boyuttur. | Provider daha büyük response döndürüyorsa artırın. Gereksiz büyütmek RSS riskidir. |
| `reactor.dubbo.native-connections-per-endpoint` | `16` | Her provider endpoint için Rust keepalive TCP connection limitidir. | Low-RSS için `2`. Balanced kullanım için `16` iyi başlangıçtır. |
| `reactor.dubbo.native-async-workers` | `2` | Async Dubbo çağrı işleyici worker sayısıdır. | Low-RSS için küçük tutun. Throughput için benchmark ile artırın. |
| `reactor.dubbo.native-async-queue-capacity` | `128` | Async çağrı queue limitidir. Doluysa çağrı fail-fast olur. | Her zaman bounded kalsın. Worker ve route bulkhead ile birlikte ayarlayın. |

### ZooKeeper ve Official Mode Ayarları

| Property | Varsayılan | Ne İşe Yarar? | Ne Zaman Değiştirilir? |
| --- | ---: | --- | --- |
| `reactor.dubbo.registry-timeout-ms` | `3000` | ZooKeeper connect/operation timeout. | Sadece ZooKeeper discovery modunda anlamlıdır. |
| `reactor.dubbo.registry-session-timeout-ms` | `30000` | ZooKeeper session timeout. | Registry ortamınız farklı değer istiyorsa değiştirin. |
| `reactor.dubbo.connections` | `1` | Official mode connection ayarıdır. Native modda `native-connections-per-endpoint` kullanılır. | Official mode kullanıyorsanız ayarlayın. |
| `reactor.dubbo.share-connections` | `1` | Official mode shared connection ayarıdır. | Official mode için anlamlıdır. |
| `reactor.dubbo.refer-thread-num` | `1` | ZooKeeper provider refresh worker sayısıdır. Request worker değildir. | Çok sayıda reference aynı anda refresh oluyorsa artırılabilir. |
| `reactor.dubbo.runtime-profile` | `low-rss` | Host uygulamanın okuyabileceği profil bilgisidir. | `low-rss`, `balanced-dubbo`, `throughput`, `default` değerlerinden birini kullanın. |

## Başlangıç Profilleri

Low-RSS:

```properties
reactor.dubbo.transport=native
reactor.dubbo.providers=provider-1:20880
reactor.dubbo.retries=0
reactor.dubbo.timeout-ms=800
reactor.dubbo.max-inflight=64
reactor.dubbo.native-connections-per-endpoint=2
reactor.dubbo.native-async-workers=2
reactor.dubbo.native-async-queue-capacity=64
```

Balanced:

```properties
reactor.dubbo.transport=native
reactor.dubbo.providers=provider-1:20880,provider-2:20880
reactor.dubbo.retries=0
reactor.dubbo.timeout-ms=1200
reactor.dubbo.max-inflight=512
reactor.dubbo.native-connections-per-endpoint=16
reactor.dubbo.native-async-workers=8
reactor.dubbo.native-async-queue-capacity=1024
```

Low-RSS profilde memory daha kontrollüdür; overload durumunda 503 kabul edilir.

Balanced profilde Dubbo throughput daha iyi olur. Fakat provider kapasitesi ve route bulkhead de buna göre ayarlanmalıdır.

## Native Mode ve Official Mode Farkı

Native mode varsayılandır. Amacı consumer JVM içinde gereksiz class, thread ve native buffer maliyetini azaltmaktır. Bu modda resmi Dubbo `ReferenceConfig`, registry directory, official remoting, Netty, Java ZooKeeper client ve Hessian Lite hot no-arg `byte[]` path için zorunlu değildir.

Official mode, tam Dubbo davranışı gereken yerlerde kullanılır. Daha fazla özellik sağlar, fakat RSS ve classpath maliyeti daha yüksektir.

## Şu An Bilerek Kapsam Dışı Bırakılanlar

Native mode şu özellikleri hedeflemez:

- Dubbo config-center ve metadata-center.
- `/consumers` altında consumer registration.
- Resmi Dubbo router/governance kuralları.
- Triple, REST protocol, HTTP/2, callback, generic invocation.
- Full official filter-chain uyumluluğu.
- Tek connection üzerinde multiplexed request demux.

Bu özellikler gerekiyorsa official mode veya ayrı bir Dubbo integration service daha doğru olur.

## Derleme

```powershell
mvn clean verify
```

Üretilen paketler:

- `target/java-rust-dubbo-0.1.0-rc3.jar`
- `target/java-rust-dubbo-0.1.0-rc3-native-static.jar`
- `target/java-rust-dubbo-0.1.0-rc3-sources.jar`
