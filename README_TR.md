# java-rust-dubbo

Java/Rust REST framework icin minimal Dubbo consumer adaptoru.

Bu proje Spring Boot starter mantigiyla calismaz. Amac, Java business logic akisini korurken Dubbo consumer ihtiyacini minimum RSS ve minimum dependency ile cozmek.

## Kullaniciya Ne Saglar?

- REST tarafinda Rust Hyper I/O plane korunur.
- Handler, service ve component kodu Java'da kalir.
- Dubbo provider cagrisini Java service icinden yaparsiniz.
- Statik provider listesi kullanildiginda consumer JVM icinde ZooKeeper, Netty ve resmi Dubbo runtime yuklenmez.
- Rust tarafi Dubbo TCP frame, connection pool, async queue, timeout ve response limitlerini sahiplenir.
- Sicak path icin `byte[]` no-arg metodlarda Java Hessian allocation maliyeti azalir.

## En Dusuk RSS Kullanim

BEST:

```properties
reactor.dubbo.transport=native
reactor.dubbo.providers=catalog-provider:20880,catalog-provider-2:20880
reactor.dubbo.retries=0
reactor.dubbo.timeout-ms=1000
reactor.dubbo.max-inflight=256
reactor.dubbo.native-connections-per-endpoint=16
reactor.dubbo.native-async-workers=2
reactor.dubbo.native-async-queue-capacity=128
```

Bu modda provider adresleri uygulamaya statik verilir. Kubernetes icinde bunu Service DNS, sidecar-generated provider list veya config ile yonetmek daha dusuk RSS verir.

ACCEPTABLE: Provider listesi bos birakilirsa ZooKeeper discovery calisir. Bu otomatik provider degisimini takip eder, fakat Java tarafinda ekstra thread/class/RSS maliyeti getirir.

ANTI-PATTERN: Sadece basit consumer cagri ihtiyaci icin Dubbo Spring Boot starter, config-center, metadata-center, Curator ve Netty stack'ini REST process'ine almak.

## Maven

```xml
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>java-rust-dubbo</artifactId>
  <version>0.1.0-rc1</version>
</dependency>
```

Bu jar tek basina adapter kodunu tasir. Native modda Rust JNI fonksiyonlari `rust-java-rest` framework native library tarafindan saglanir.

## Ornek Kullanim

```java
@Configuration
public final class DubboClientConfiguration {
    private final NativeDubboConsumerClient client = NativeDubboConsumers.create();

    @Bean
    public CatalogClient catalogClient() {
        DubboReferenceSpec<CatalogProviderApi> spec = DubboReferenceSpec.of(CatalogProviderApi.class);
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

```java
public final class CatalogClient {
    private final NativeDubboMethodInvoker<byte[]> nestedCatalogJson;

    public CatalogClient(NativeDubboMethodInvoker<byte[]> nestedCatalogJson) {
        this.nestedCatalogJson = nestedCatalogJson;
    }

    public byte[] nestedCatalogJson() {
        return nestedCatalogJson.invoke();
    }
}
```

Async handler kullaniyorsaniz:

```java
public CompletionStage<byte[]> nestedCatalogJsonAsync() {
    return nestedCatalogJson.invokeAsync();
}
```

## Uretim Karari

Low-RSS servislerde native + static provider modu dogru default'tur.

Yuksek concurrency altinda "her istek mutlaka beklesin ve 200 donsun" isteniyorsa sadece worker artirmak yeterli degildir. Route-level bulkhead, timeout, queue capacity ve provider kapasitesi birlikte ayarlanmalidir.

Resmi Dubbo governance, router, metadata-center, config-center veya generic invocation gerekiyorsa bu lite adapter dogru arac degildir. Bu durumda official mode veya ayri bir integration service daha guvenlidir.

## Build

```powershell
mvn clean verify
```

Uretilen paketler:

- `target/java-rust-dubbo-0.1.0-rc1.jar`
- `target/java-rust-dubbo-0.1.0-rc1-sources.jar`
