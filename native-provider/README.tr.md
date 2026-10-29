# İsteğe Bağlı Native Provider

[English](README.md) | [Türkçe](README.tr.md) | [Proje](../README.tr.md)

Senkron ve kapalı şemalı Java servisleri için `PlainDubboProvider` alternatifidir.
Generated Java iş kodunuzu çağırır; ortak ABI-3 JNI/Tokio motoru klasik Dubbo TCP
ve Hessian frame işlemlerini yürütür. `rust-spring` içine ikinci Dubbo acceptor
eklenmez; ağ katmanı, deadline ve native sahiplik uygulaması çoğaltılmaz.
Mevcut provider varsayılanları değişmez.

**Modüller yalnız kaynak çalışmasıdır, yayımlanmadı.** Aşağıdaki `0.9.0`
koordinatları kaynak derlemesine aittir, yayımlanmış paket değildir. Runtime
`java-rust-dubbo-runtime:0.7.1` olmakla birlikte güncel sınırlı/parser-policy
native yetenekleri de gerekir. Güncel dondurulmuş kaynağın Linux işlev kontrolleri
ve odak sıcak yük karşılaştırması EmaTech'te başarılıdır. Windows rebuild ve
koordineli dağıtım kabulü açıktır.

## Önce Sample'ı Kullanın

Aynı Java servisini kullanan çalıştırılabilir resmî/native profiller
[sample rehberindedir](../../rest-sample-dubbo-provider/docs/NATIVE_PROVIDER.tr.md).
Portlar, ayarlar, shaded-JAR komutları ve tipli 32/512 satırlık HTTP kullanımı
orada açıklanır.

Başka bir uygulamada adaptörü ekleyin ve processor'ı açıkça etkinleştirin:

```xml
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>java-rust-dubbo-native-provider</artifactId>
  <version>0.9.0</version>
</dependency>
```

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-compiler-plugin</artifactId>
  <version>3.13.0</version>
  <configuration>
    <release>21</release>
    <proc>full</proc>
    <annotationProcessorPaths>
      <path>
        <groupId>com.reactor</groupId>
        <artifactId>java-rust-dubbo-native-provider-codegen</artifactId>
        <version>0.9.0</version>
      </path>
    </annotationProcessorPaths>
    <annotationProcessors>
      <annotationProcessor>com.reactor.rust.dubbo.nativeprovider.codegen.NativeProviderProcessor</annotationProcessor>
    </annotationProcessors>
  </configuration>
</plugin>
```

Gereken diğer processor'ları açıkça birleştirin; bu processor'ı production
classpath'e koymayın. Adaptörün production bağımlılıkları, transitifleri dışlanan
eski `java-rust-dubbo:0.9.0` API'si ile ortak runtime'dır. Spring veya resmî
Dubbo/Netty/HessianLite gerekmez. Eski API ile ortak package yalnız classpath'te
desteklenir; JPMS module path desteklenmez.

İş interface'ini değiştirmeden binding tanımlayın:

```java
@GenerateNativeProvider(service = CustomerCommandService.class, version = "0.0.0")
final class CustomerDefinition {}
```

Uygulama başlangıcında mevcut servis implementation'ını bağlayın:

```java
var binding = CustomerDefinition__NativeProvider.bind(implementation,
    PlainDubboProvider.ServiceExecutionConfig.bounded(4,
        Map.of("createCustomerTyped", 2)));
var provider = NativeProviderFactory.start(
    NativeProviderFactory.Options.defaults(20880), List.of(binding), null);
// Süreci açık tutun; provider.close() çağrısını shutdown sahibine bağlayın.
```

Tipler `com.reactor.rust.dubbo.provider`, sample contract
`com.reactor.rust.dubbo.sample.CustomerCommandService`, `List`/`Map` ise
`java.util` paketindedir. İsteğe bağlı üçüncü argüman
`ProviderDispatcherDecorator` her servis için başlangıçta bir kez uygulanır.
Sınırlı servis/metot kapıları decorator/iş kodundan önce çalışır, iş hatasında da
permit bırakılır. Adaptör reflective invocation, method-handle fallback,
discovery veya gizli iş çağrısı tekrarı kullanmaz. Consumer ile wire interface,
group ve version tam eşleşmelidir; statik adres veya Kubernetes Service DNS kullanın.

Güncel SO'yu açıkça
`-Dreactor.dubbo.native.path=/opt/reactor/native/librust_dubbo.so` ile seçin.
Factory, metot kaydı ve bind öncesi wire/bağlantı/response kabulü ile parser-policy
metriklerini denetler. Bu yetenekleri eksik ABI-3 dosyası başlangıçta reddedilir;
metrikler tek başına native dosyanın kaynak/provenance doğrulaması değildir. Platform
artifact/classifier veya native dosyası otomatik kurulmaz. Güncel Windows DLL,
parser-policy ve paket rebuild işi 17. maddede açıktır; eski Windows/paket dosyası
doğrulanan Linux kaynağının kanıtı değildir.

## Şema Ve Constructor Kuralları

Senkron contract; primitive/boxed scalar, `String`, `byte[]`, public enum,
public nongeneric döngüsüz record, somut `List<T>` ve `Map<String,V>` kullanabilir.
Referans tipli değerler null olabilir. Girişte record canonical constructor
gerçekten çağrılır; compact-constructor validation/normalization da çalışır.
Constructor bypass yapılmaz.

| Sınır | Değer |
| --- | ---: |
| Servis / servis başına metot / toplam metot | 64 / 64 / 1024 |
| Metot adı / JVM descriptor / group veya version | 128 / 2048 / 256 karakter |
| Codec tip derinliği / karmaşık tip | 16 / 128 |
| Record alanı / enum sabiti | 64 / 256 |
| Giriş koleksiyon öğesi | Ayarlı limit, en fazla 4096 |
| Generated çıkış koleksiyon öğesi | 4096 |

Generic servis/metot/record, overload, future/async completion, bean,
raw/wildcard koleksiyon, dinamik `Object` ve döngülü şema desteklenmez.
Bilinmeyen/eksik/tekrar record alanı, null/tekrar map anahtarı, giriş koleksiyonu alias'ı
ve çözülemeyen referans fail-closed reddedilir. Tamamlanan record referansları
tip kontrolünden geçer. Writer nesne kimliğini korumaz; şema değişimini koordine
edin. Bu genel bir Jackson serializer değildir.

Olağan provider codec'leri String, dizi, record, argument array, boxing ve parser
tahsisatı yapar. Adaptör yardımcısı negatif floating-point zero'yu korur.
Genel sıfır allocation, sıfır String veya zero-copy garantisi yoktur.
Consumer direct-JSON ayrı bir yetenektir: DTO constructor/validation'ını atlayabilir
ve kendi şema/sahiplik sınırlarına tabidir.

## Ayarlar Ve Kaynak Kabulü

`Options` sırası: `port, ioWorkers, businessWorkers, queueCapacity,
maxPayloadBytes, requestTimeoutMs, drainTimeoutMs, maxCollectionItems`.

| Seçenek | `Options.defaults(20880)` | İzin verilen |
| --- | ---: | --- |
| Port | 20880 | 1..65535 |
| IO worker | 1 | 1..8 |
| İş worker'ı | 4 | 1..64 |
| Queue kapasitesi | 64 | 1..1024 |
| Payload baytı | 262144 | 1024..1048576 |
| İstek timeout / drain timeout | 5000 / 5000 ms | 1..60000 / 0..60000 ms |
| Giriş koleksiyon öğesi | 1024 | 1..4096 |

`(businessWorkers + queueCapacity) * maxPayloadBytes <= 32 MiB`.
API varsayılanı 17 MiB mantıksal istek kabul kapasitesi demektir; sample queue 16
kullanır ve 5 MiB eder. İkisi de ayrılmış slab veya toplam RSS değildir.

| Native kaynak | Sınır / ömür |
| --- | --- |
| Bağlantı | Listener başına `min(businessWorkers + queueCapacity, 128)`; süreç genelinde 256 |
| Response kabulü | Listener başına `min(businessWorkers + queueCapacity, 256)`; süreç genelinde 256 |
| Output registry | Sabit 256 slot; pozitif handle'da 8 index, 55 generation biti |
| Wire baytları | Süreç genelinde 32 MiB; tutulan istek, response ve hata frame'leri |
| Java codec context | İş worker'ı kadar sabit slot; sınırlı CAS borrow, `finally` ile detach |

Bağlantı kredisi iki socket yarısı ve bekleyen writer'ları izler. Response kredisi,
callback ve outbound-body sahipleri bitene kadar korunur. Wire kredisi bildirilen
body için tahsisattan önce alınır; büyümede eski ve yeni bellek birlikte sayılır.
Çözülmüş Java/Rust nesneleri, task metadata'sı ve diğer süreç belleği bu bütçelere
dahil değildir. Ortak motoru doğrudan kullananların olağan 8 MiB payload varsayılanı
korunur; 1..33553408 bayt dışı motor limitleri başlangıçta reddedilir. Bu,
adaptörün daha sıkı 1 MiB tavanından ayrıdır.

Provider reader policy 1; çağrı derinliğini 64, işi 65536 birim, class/type
tablolarını 128 kayıt, class alanlarını 64, metadata adını 4096 bayt ve toplam
ücretlendirilen metadata'yı 64 KiB ile sınırlar. Oluşturulan attachment
değerleri/sahiplenilen adlar sayılır; atlanan argüman grafiği oluşturulmaz.
Descriptor, recursive çözümleme öncesi en fazla 64 dizi boyutu ve 256 parametre
için denetlenir. Bunlar parser bütçeleridir; toplam RSS veya her girdiye karşı
güvenlik garantisi değildir.

## Yaşam Döngüsü Ve Kalan Kilitler

Ayrı süreç ve tek kayıt sahibi kullanın. Native metot kaydında unregister yoktur;
başarısız olanı dahil bir native başlangıç denemesinden sonra yeni JVM ile
başlatın. Factory'yi aynı süreçte doğrudan runtime provider ile karıştırmayın.
Listener bütün ağ arayüzlerine bind eder; erişimi ayrılmış ortamla sınırlayın.

Output belleği ve bekleyen completion sahipliği, generation etiketli 256 atomik
slotu paylaşır; ikisi için de ayrı mutex korumalı map yoktur. Completion, slota
özel erişim bırakıldıktan sonra gönderilir; pending metrikleri atomik bayrakları
okur. Eski handle yeni generation'ı değiştiremez; generation biterse sarma yerine
slot kullanımdan çıkarılır. Slot çakışması beklemeden reddedilir; sınırsız registry
retry döngüsü yoktur. Handle başına bir Java writer olmalı; büyümede eski view
bırakılmalı ve yalnız son yazımdan sonra completion verilmelidir.

Timeout/drain, aktif Java bellek ödüncünü değil ağ bekleyişini iptal eder.
Ödünç storage/kabul kredisi gerçek callback tamamlanana kadar tutulur; outbound
response belleği socket write/drop sonuna kadar kredisini korur. Close yeni
dispatch'i reddeder, sınırlı drain ister, tekrar çağrılabilir ve Java iş kodunu
zorla durdurmaz. Daemon bağlı iş worker'ları doğal JVM çıkışını engellemez.
Kubernetes termination grace, drain ve güvenlik payından uzun olmalıdır.

**Motor bütünüyle lock-free veya wait-free değildir.** Provider-registry mutex'i
başlangıç, durdurma ve metrik okumasında kullanılır, her RPC'de kullanılmaz.
Bağlantı başına yazımlar Tokio async mutex kullanır: bekleme OS thread'ini
bloklamaz, ancak tam frame'leri sıraya koyar; yavaş yazım aynı bağlantıdaki
sonraki yanıtları geciktirebilir. Mevcut consumer-response registry ayrıdır ve bu
provider yolunda değildir. Sınırlı sahiplik ve tahsisat maliyetleri kalır; output
ve pending-map mutex'lerinin kalkması uçtan uca gecikme veya bellek garantisi değildir.

## Metrikler Ve Doğrulama

`com.reactor.rust.dubbo.runtime.NativeDubboBridge.metricsJson()` ile okuyun;
diagnostics'i dışarıya açmayın.

| Metrikler | Anlamı |
| --- | --- |
| `providerWireBytesUsed/Limit`, `providerWireRejections` | Wire-byte sahipliği ve ret |
| `providerConnectionLimit`, `providerConnectionAdmissionUsed/Limit/Rejections` | Listener toplamları ve süreç bağlantı kabulü |
| `providerResponseLimit`, `providerResponseAdmissionUsed/Limit/Rejections` | Listener toplamları ve süreç response kabulü |
| `providerPendingResponses`, `providerOutputHandles`, `providerOutputBytes` | Bekleyen completion ve sahiplenilen çıktı belleği |
| `providerReaderPolicyVersion`, `providerReaderDepthLimit`, `providerReaderWorkLimit`, `providerReaderMetadataBytesLimit` | Zorunlu parser-policy yetenekleri |

Yukarıdaki eğik çizgili adlar, aynı önek ve ayrı soneklerle ayrı JSON alanlarıdır.
Eşzamanlı pending/output metrikleri yaklaşık anlık görüntüdür. Geç callback/outbound body,
timeout sonrası sahipliği haklı olarak sürdürebilir; leak demeden sahibi inceleyin.
Gerçek completion/drain sonrasında sahipliğin boş duruma döndüğünü doğrulayın.

Koordineli bağımlılıklar hazırlandıktan sonra workspace kökünden yalnız derleme:

```bash
mvn -B -ntp -f java-rust-dubbo/native-provider-build/pom.xml -Dmaven.test.skip=true install
```

Testleri sınırlı kaynaklara sahip, ayrılmış test ortamınızda güncel doğrulanmış
native dosyayla çalıştırın. Kayıtlı doğrulamamız EmaTech'te yapıldı:

```bash
NATIVE_LIB=/opt/reactor/native/librust_dubbo.so
mvn -B -ntp -f java-rust-dubbo/native-provider-build/pom.xml \
  "-DtrackA.native.path=$NATIVE_LIB" \
  "-DtrackA.proof.dir=$PWD/native-provider-proof" test
```

Integration testi Linux `java` ve Windows `java.exe` destekler; güncel Windows
artifact doğrulaması yine açıktır. Eksik native girdi/yetenek sessizce atlanmaz,
hata verir. Child class-load kontrolü generated/JNI sınıflarını arar; resmî
Dubbo, Netty, Spring ve method-handle fallback'i reddeder. Frame yardımcısı yalnız
test içindir, ikinci production transport değildir.

27 Ekim 2026'da güncel dondurulmuş kaynak EmaTech işlev kontrollerini geçti:
74 Rust testi (57 native + 17 core) ve 171 Java testi (164 + 7 sample); sınırlı
başlık planının güvenli öğrenilmesi ve yanıt/completion sahipliği dahildir.
Desteklenmeyen iki OpenJ9 allocation kontrolü iptal edildi, başarılı sayılmaz;
ayrı sekiz kalibre edilmiş HotSpot allocation kontrolü başarılıdır. Linux derleme,
fmt ve Clippy kontrolleri başarılıdır; en yüksek GLIBC sembolü 2.14 olarak kaldı.

Ayrı sıcak yük karşılaştırmasında, uygulama başına 1 CPU/128 MiB altında dört
yeni çiftin dokuz hücresi de geçti. Resmî motora göre 32/512 satırlı hedeflerde
başarılı RPS %15,70–21,43 arttı; p99 %4,27–17,63 ve provider RSS 19,90–27,10 MiB
azaldı. Dubbo çağırmayan üç health kontrol hücresi de sınırlar içinde kaldı.
Final heap eşlenmiş medyanı 7,04 MiB azaldı.

Native motor, gerçek scalar wire argümanlarından öğrendiği bağlantı başına tek
immutable protokol başlık planını kullanır; metadata en fazla 1 KiB'dır. Her
argüman yine doğrulanır. Değişen metadata ve graph'lar mevcut sınırlı ayrıştırıcıya
gider. Yanıt cache edilmez. Java iş kodu ve public contract değişmedi.

Bu sıcak yük kabulüdür; cold-start, idle sonrası ilk çağrı ve bütün ürünün birleşik
kabulü değildir. Ayrıntılı karşılaştırma ve profil ayarları
[sample rehberindedir](../../rest-sample-dubbo-provider/docs/NATIVE_PROVIDER.tr.md).
Windows DLL/parser-policy ve koordineli paket/dağıtım rebuild işi 17'de açıktır.
