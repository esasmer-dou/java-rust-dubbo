# java-rust-dubbo

`java-rust-dubbo`, Java/Rust REST framework icinde Dubbo provider cagirmak icin hazirlanmis kucuk bir consumer kutuphanesidir.

Kullanim fikri basittir:

- REST handler, service ve component kodunuz Java'da kalir.
- HTTP server ve native I/O tarafi Rust Hyper ile devam eder.
- Dubbo provider cagrisini Java servisinizden yaparsiniz.
- Isterseniz Dubbo TCP data-plane Rust tarafinda calisir ve consumer JVM daha kucuk kalir.
- ZooKeeper, Netty ve resmi Dubbo client stack'i varsayilan olarak zorunlu degildir.

Bu kutuphane, "dependency ekleyince her seyi otomatik yapsin" yaklasimindan ziyade acik ve kontrollu kurulum yapar. Bu da memory, thread ve latency davranisini daha kolay yonetmenizi saglar.

## Ne Zaman Kullanilir?

Su durumlarda uygundur:

- Java/Rust REST uygulamanizin Dubbo provider cagirmasi gerekiyorsa.
- Spring Boot starter veya full Dubbo client stack'i REST process'ine almak istemiyorsaniz.
- Kubernetes icinde provider adreslerini Service DNS, config veya sidecar-generated provider list ile verebiliyorsaniz.
- Provider JSON veya binary body'yi hazir `byte[]` olarak dondurebiliyorsa.
- Dusuk RSS, dusuk allocation ve kontrollu backpressure onemliyse.

Su durumlarda resmi Dubbo stack daha dogru olabilir:

- Dubbo config-center, metadata-center veya router/governance kurallarina ihtiyaciniz varsa.
- Callback, generic invocation, Triple, REST protocol veya full filter-chain davranisi gerekiyorsa.
- Consumer registration ve tum resmi Dubbo uyumlulugu zorunluysa.

## Maven

```xml
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>java-rust-dubbo</artifactId>
  <version>0.1.0-rc1</version>
</dependency>
```

Native modda bu dependency kucuk tutulur. Uygulamaniza ZooKeeper, Netty, Hessian Lite veya resmi Dubbo client stack otomatik olarak tasinmaz.

Native modun calismasi icin Java/Rust framework native library de yuklu olmalidir. `rust-java-rest` icinde bu native library framework tarafindan yuklenir. Standalone testlerde `rust_hyper` kutuphanesini `java.library.path` ile gorunur hale getirmek gerekir.

## Quick Start

Bu bolum, bir Java/Rust REST uygulamasini adim adim Dubbo consumer haline getirir.

### 1. Dependency Ekleyin

Yukaridaki Maven dependency'yi uygulama `pom.xml` dosyaniza ekleyin.

Dubbo Spring Boot starter eklemeniz gerekmez. Bu kutuphane framework icinde acik bean/config mantigiyla kullanilir.

### 2. Temel Property'leri Ekleyin

En dusuk RSS icin native transport ve static provider listesi ile baslayin:

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

Bu ayarlarin anlami:

- `transport=native`: Dubbo TCP cagrisini Rust native data-plane yapar.
- `providers=host:port`: Consumer hangi provider'a gidecegini bilir. Bu alan doluysa Java ZooKeeper client baslamaz.
- `retries=0`: Gizli retry yapmaz. Latency daha ongorulebilir olur.
- `max-inflight`: Ayni anda kac Dubbo cagrisinin devam edebilecegini sinirlar.
- `native-async-queue-capacity`: Queue buyumesini sinirlar. Queue dolarsa kontrollu hata donulur, memory sisirilmez.

### 3. Provider Interface'ini Tanimlayin

Provider hangi Java interface'i export ediyorsa consumer tarafinda ayni interface adini ve method imzasini kullanin.

Mevcut en hizli native path icin provider'in no-arg `byte[]` donmesi idealdir:

```java
package com.example.catalog;

public interface CatalogProviderApi {
    byte[] nestedCatalogJson();
}
```

Bu `byte[]` JSON olabilir. REST handler tarafinda bunu direkt `RawResponse.json(...)` ile dondurebilirsiniz. Boylece buyuk Java DTO graph olusturmadan provider cevabini HTTP response'a tasirsiniz.

### 4. Consumer Client'i Uygulama Baslangicinda Bir Kez Olusturun

Dubbo client request basina olusturulmaz. Uygulama baslangicinda bir kez olusturulur ve tum request'lerde tekrar kullanilir.

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

Burada dikkat edilmesi gerekenler:

- `NativeDubboConsumerClient` tek instance olarak yasamalidir.
- `NativeDubboMethodInvoker` startup'ta hazirlanir, request sirasinda tekrar hesaplanmaz.
- `shutdown()` icinde `client.close()` cagrilmalidir. Bu native client ve connection kaynaklarini temizler.

### 5. Java Client Wrapper Yaziniz

Handler veya business service icinde direkt Dubbo detaylariyla calismamak daha temizdir. Kucuk bir wrapper class yazin.

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

`invoke()` senkron cagridir.

`invokeAsync()` async cagridir. Handler `CompletionStage` donebiliyorsa bu yol daha dogrudur; request worker thread'i Dubbo cevabini bloklayarak beklemez.

### 6. Handler Icinden Kullanin

Provider JSON donduruyorsa bunu raw JSON response olarak sunabilirsiniz:

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

Provider hata verirse veya timeout olursa uygulama katmaninda kontrollu HTTP response donun. Ornegin 503 ve kisa bir JSON hata body yeterlidir.

### 7. Route Seviyesinde Limit Ekleyin

Native client kendi icinde `max-inflight` ve queue limitleriyle korunur. Fakat HTTP endpoint seviyesinde de limit olmasi daha dogrudur.

Dusuk RSS servislerde route bulkhead kucuk tutulur. Overload durumunda kontrollu 503 donmek, binlerce request'i queue'da bekletmekten daha sagliklidir.

### 8. Production Oncesi Test Edin

Canliya cikmadan once su senaryolari test edin:

- Dubbo kapali idle RSS.
- Dubbo acik idle RSS.
- Load sonrasi RSS ve 30 saniye idle sonrasi RSS.
- c64, c256, c512, c1000 concurrency.
- Provider restart oldugunda consumer tekrar cagri yapabiliyor mu?
- Provider yavasladiginda timeout calisiyor mu?
- Queue doldugunda sistem memory sisirmeden kontrollu hata donuyor mu?
- Uygulama kapanirken native client release ediliyor mu?

## Property Referansi

Tum ayarlar `reactor.dubbo.` prefix'i ile baslar.

Degerler uc yerden gelebilir:

- Java `Properties` objesi.
- Java system property.
- Environment variable.

System property ve environment variable, `Properties` objesindeki degerin ustune yazar.

Environment variable yazarken buyuk harf kullanin, `.` ve `-` karakterlerini `_` yapin:

```text
REACTOR_DUBBO_TIMEOUT_MS=1000
REACTOR_DUBBO_NATIVE_ASYNC_WORKERS=8
```

### Temel Ayarlar

| Property | Varsayilan | Ne Ise Yarar? | Ne Zaman Degistirilir? |
| --- | ---: | --- | --- |
| `reactor.dubbo.application-name` | `rust-java-rest-dubbo-consumer` | Consumer adidir. Log, diagnostic ve official mode metadata icin anlamlidir. | Servis adinizla degistirin. Ornek: `orders-api`. |
| `reactor.dubbo.transport` | `native` | Dubbo cagrisini kimin yapacagini belirler. `native` Rust data-plane kullanir, `official` resmi Dubbo stack'i kullanir. | Dusuk RSS icin `native` kalsin. Full Dubbo ozellikleri gerekiyorsa `official` kullanin. |
| `reactor.dubbo.providers` | bos | Static provider listesidir. Format: `host:port,host2:port`. Doluysa Java ZooKeeper baslamaz. | Kubernetes Service DNS, config veya sidecar provider list kullaniyorsaniz doldurun. |
| `reactor.dubbo.registry-address` | `zookeeper://127.0.0.1:2181` | `providers` bos ise ZooKeeper adresidir. | Dynamic provider discovery gerekiyorsa ayarlayin. |
| `reactor.dubbo.registry-root` | `dubbo` | ZooKeeper icinde provider node'larinin root path'idir. | Provider'lar farkli root altinda register oluyorsa degistirin. |
| `reactor.dubbo.registry-check` | `false` | Registry yoksa startup davranisini etkiler. | Rolling deploy icin genelde `false` kalir. Kritik dependency ise readiness check ekleyin. |

### Cagri Davranisi

| Property | Varsayilan | Ne Ise Yarar? | Ne Zaman Degistirilir? |
| --- | ---: | --- | --- |
| `reactor.dubbo.timeout-ms` | `1000` | Tek bir Dubbo cagrisinin ne kadar bekleyecegini belirler. | Provider normalde daha yavas ise artirin. p99 onemliyse dusuk tutun. |
| `reactor.dubbo.retries` | `0` | Basarisiz cagri sonrasi kac ek deneme yapilacagini belirler. | Sadece idempotent methodlarda artirin. Aksi halde duplicate is ve p99 artisi yaratabilir. |
| `reactor.dubbo.check` | `false` | Reference startup'ta provider varligini zorunlu gorsun mu? | Rolling restartlarda `false` daha yumusaktir. Dependency zorunluysa readiness ile kontrol edin. |
| `reactor.dubbo.lazy` | `false` | Reference'in gec olusturulmasi davranisidir. | Startup'ta Dubbo hazirligi istemiyorsaniz kullanilabilir. |
| `reactor.dubbo.protocol` | `dubbo` | Classic Dubbo protocol adidir. | Genelde degistirilmez. |
| `reactor.dubbo.serialization` | `hessian2` | Dubbo serialization adidir. | Native mode icin `hessian2` kalsin. |
| `reactor.dubbo.cluster` | `failfast` | Desteklenen degerler: `failfast`, `failover`. | Latency icin `failfast`. Idempotent cagri ve provider alternatifi varsa `failover`. |
| `reactor.dubbo.loadbalance` | `random` | Desteklenen degerler: `random`, `roundrobin`. | Varsayilan `random` yeterlidir. Basit esit dagilim icin `roundrobin`. |

### Native Kaynak Limitleri

| Property | Varsayilan | Ne Ise Yarar? | Ne Zaman Degistirilir? |
| --- | ---: | --- | --- |
| `reactor.dubbo.max-inflight` | `256` | Ayni anda devam eden native Dubbo cagri limitidir. | Dusuk RSS icin azaltin. Provider kapasitesi yuksekse ve testlerle dogrulandiysa artirin. |
| `reactor.dubbo.max-response-bytes` | `8388608` | Native Dubbo response frame icin maksimum boyuttur. | Provider daha buyuk response donduruyorsa artirin. Gereksiz buyutmek RSS riskidir. |
| `reactor.dubbo.native-connections-per-endpoint` | `16` | Her provider endpoint icin Rust keepalive TCP connection limitidir. | Low-RSS icin `2`. Balanced kullanim icin `16` iyi baslangictir. |
| `reactor.dubbo.native-async-workers` | `2` | Async Dubbo cagri isleyici worker sayisidir. | Low-RSS icin kucuk tutun. Throughput icin benchmark ile artirin. |
| `reactor.dubbo.native-async-queue-capacity` | `128` | Async cagri queue limitidir. Doluysa cagri fail-fast olur. | Her zaman bounded kalsin. Worker ve route bulkhead ile birlikte ayarlayin. |

### ZooKeeper Ve Official Mode Ayarlari

| Property | Varsayilan | Ne Ise Yarar? | Ne Zaman Degistirilir? |
| --- | ---: | --- | --- |
| `reactor.dubbo.registry-timeout-ms` | `3000` | ZooKeeper connect/operation timeout. | Sadece ZooKeeper discovery modunda anlamlidir. |
| `reactor.dubbo.registry-session-timeout-ms` | `30000` | ZooKeeper session timeout. | Registry ortaminiz daha farkli deger istiyorsa degistirin. |
| `reactor.dubbo.connections` | `1` | Official mode connection ayaridir. Native modda `native-connections-per-endpoint` kullanilir. | Official mode kullaniyorsaniz ayarlayin. |
| `reactor.dubbo.share-connections` | `1` | Official mode shared connection ayaridir. | Official mode icin anlamlidir. |
| `reactor.dubbo.refer-thread-num` | `1` | ZooKeeper provider refresh worker sayisidir. Request worker degildir. | Cok sayida reference ayni anda refresh oluyorsa artirilabilir. |
| `reactor.dubbo.runtime-profile` | `low-rss` | Host uygulamanin okuyabilecegi profil bilgisidir. | `low-rss`, `balanced-dubbo`, `throughput`, `default` degerlerinden birini kullanin. |

## Baslangic Profilleri

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

Low-RSS profilde memory daha kontrolludur, overload durumunda 503 kabul edilir.

Balanced profilde Dubbo throughput daha iyi olur, fakat provider kapasitesi ve route bulkhead de buna gore ayarlanmalidir.

## Native Mode Ve Official Mode Farki

Native mode varsayilandir. Amaci consumer JVM icinde gereksiz class, thread ve native buffer maliyetini azaltmaktir. Bu modda resmi Dubbo `ReferenceConfig`, registry directory, official remoting, Netty, Java ZooKeeper client ve Hessian Lite hot no-arg `byte[]` path icin zorunlu degildir.

Official mode, tam Dubbo davranisi gereken yerlerde kullanilir. Daha fazla ozellik verir, fakat RSS ve classpath maliyeti daha yuksektir.

## Su An Bilerek Kapsam Disi Birakilanlar

Native mode su ozellikleri hedeflemez:

- Dubbo config-center ve metadata-center.
- `/consumers` altina consumer registration.
- Resmi Dubbo router/governance kurallari.
- Triple, REST protocol, HTTP/2, callback, generic invocation.
- Full official filter-chain uyumlulugu.
- Tek connection uzerinde multiplexed request demux.

Bu ozellikler gerekiyorsa official mode veya ayri bir Dubbo integration service daha dogru olur.

## Build

```powershell
mvn clean verify
```

Uretilen paketler:

- `target/java-rust-dubbo-0.1.0-rc1.jar`
- `target/java-rust-dubbo-0.1.0-rc1-sources.jar`
