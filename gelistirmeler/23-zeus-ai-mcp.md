# 23 — zeus-ai-mcp (uygulama servislerini MCP tool olarak yayınlama)

`zeus-ai-mcp`, uygulamanın **mevcut `@Tool` metotlarını** harici AI ajanlarına (Claude, kurumsal
chat uygulaması) MCP (Model Context Protocol) üzerinden açan modüldür. Yeni bir tool anotasyonu
icat etmez, yeni bir veri yolu açmaz: yayın yüzeyini uygulama **açıkça** bildirir, framework onu
audit'ler ve ucu korur.

| | |
|---|---|
| artifactId | `com.zeus:zeus-ai-mcp` |
| base paket | `com.zeus.framework.ai.mcp` |
| yetenek | `zeus.ai.mcp.enabled` (**varsayılan KAPALI**, `ai-mcp` yeteneği) |
| taşıma | streamable HTTP / WebMVC — `spring-ai-starter-mcp-server-webmvc` |
| bağımlılık | `zeus-ai`, `spring-ai-starter-mcp-server-webmvc`, `spring-boot-autoconfigure`, `spring-boot-starter-tomcat` (provided) |
| Spring AI | 2.0.1 (BOM'dan); MCP SDK `io.modelcontextprotocol.sdk` 2.0.0 (transitif, pinlenmez) |

## Süreç içi tool ↔ MCP tool: fark nedir

`zeus-ai`'deki model **süreç içidir**: uygulama `@Tool` nesnesini `ZeusAiAssistant.ask(...)`'a
varargs olarak verir, modeli uygulama seçer, döngüyü Spring AI'ın `ToolCallingAdvisor`'ı yürütür.

MCP ise aynı metodu **ağ üzerinden dışarıya** açar: modeli ve ajanı artık uygulama seçmez. Bu
yüzden ikisi aynı opt-in'e bağlanmaz ve yayın yüzeyi classpath taramasıyla belirlenmez.

```
Harici ajan ──JSON-RPC/streamable HTTP──> /api/mcp
                                             │  ZeusMcpTokenFilter (paylaşılan sır)
                                             ▼
                                   ToolCallbackProvider (zeus-ai-mcp)
                                             │  AuditingToolCallback  → audit + correlation-id
                                             ▼
                                   ProductAiTools @Tool metodu   ← DEĞİŞMEDİ
                                             ▼
                                   ProductRepository → Oracle PRODUCT_PKG
```

## Uygulamanın yazdığı tek şey: yayın bean'i

```java
@Configuration
public class McpConfig {
    @Bean
    ZeusMcpTools zeusMcpTools(ProductAiTools productAiTools) {
        return ZeusMcpTools.of(productAiTools);
    }
}
```

**Classpath taraması YOKTUR.** Bir `@Tool` metodu yazmak onu dışarı açmaz; açan şey bu satırdır.
"Bu uygulama dışarı ne açıyor?" sorusunun cevabı tek dosyadadır ve code review orada yapılır.
Gerekçe: `@Tool`'u `ask()`'e vermek çağrı başına alınan süreç içi bir karardır; "classpath'teki
tüm `@Tool` bean'lerini yayınla" kuralında yarın biri **yazma yapan** bir tool eklediğinde o da
kimse bir şey yazmadan dışarı çıkar.

`ZeusMcpTools.of(...)` doğrulamayı **bean kuruluş anında** yapar: `@Tool` metodu olmayan nesne,
iki nesnede aynı tool adı, `null` öge, aynı nesnenin iki kez verilmesi → açılışta patlar, ilk
istekte değil. Bilinçli olarak boş yayın için `ZeusMcpTools.none()`.

### Anotasyon köprüsü — neden yeni anotasyon yok

Şema dönüşümünü Spring AI'ın `ToolCallbackConverterAutoConfiguration`'ı yapar: bir
`ToolCallbackProvider` bean'i görünce `List<McpServerFeatures.SyncToolSpecification>` üretir.
Modül yalnızca kaydı toplayıp audit dekoratörüyle sarar. **Ölçülmüş sonuç** (uçtan uca,
WildFly + Oracle): tool adları metot adlarına, `@Tool(description=...)` metinleri tool
açıklamalarına, `@ToolParam(description=...)` metinleri **input şemasına** geçiyor:

```json
{"name":"findProductById",
 "description":"Verilen id'ye sahip ürünü döner; ürün yoksa null döner.",
 "inputSchema":{"type":"object","properties":{
    "id":{"type":"integer","description":"Ürünün sayısal id'si"}},"required":["id"]}}
```

Bu yüzden CLAUDE.md'nin "uygulama kodu Spring AI API'sini import etmez, **tek istisna `@Tool`**"
kuralı aynen geçerli kalır — ikinci bir istisna açılmadı.

> **Açıklama bir yorum değil, ARAYÜZ SÖZLEŞMESİDİR.** Model tool'u ve parametresini bu metinle
> seçer. `@Tool`/`@ToolParam` açıklamaları artık dışarıya yayınlanan bir API dokümanıdır.

## Yapılandırma

```properties
# YETENEK BİLDİRİMİ — bu satır olmadan hiçbir MCP bean'i kurulmaz (opt-in).
zeus.ai.mcp.enabled=true

# Paylaşılan sır ASLA repoya yazılmaz. BOŞSA AÇILIŞ REDDEDİLİR (fail-closed).
zeus.ai.mcp.token=${ZEUS_MCP_TOKEN:}

# Taşıma — İKİSİ DE AÇIKÇA YAZILMALIDIR (neden: aşağıdaki "Sessiz SSE" bölümü).
spring.ai.mcp.server.protocol=STREAMABLE
spring.ai.mcp.server.streamable-http.mcp-endpoint=/api/mcp
spring.ai.mcp.server.name=uygulama-adi
spring.ai.mcp.server.version=0.0.1
```

| property | varsayılan | ne yapar |
|---|---|---|
| `zeus.ai.mcp.enabled` | `false` | Yetenek kapısı. Yazılmazsa Spring AI'ın MCP autoconfig yığınının **tamamı** veto edilir |
| `zeus.ai.mcp.token-header` | `X-Zeus-Mcp-Token` | Sırrın taşındığı header adı |
| `zeus.ai.mcp.token` | — | Paylaşılan sır; ortam değişkeninden. Boşsa açılış reddedilir |
| `zeus.ai.mcp.audit-payload` | `false` | Audit satırına tool argümanlarını da yazar. **Kapalı**: argümanlar kişisel veri taşıyabilir |
| `zeus.ai.mcp.audit-payload-max-length` | `512` | Yukarıdaki açıkken kırpma sınırı |

**Uç yolu, protokol ve timeout `zeus.ai.mcp` altında TANIMLI DEĞİL** — `spring.ai.mcp.server.*`'da
kalır. `zeus-ai`'nin `spring.ai.openai.*`'ı ikizlememe kararıyla aynı çizgi; burada ek bir sebep
var: token filtresi korunacak yolu **taşımanın okuduğu property'den** türetir. Aynı değeri iki
yerde tutmak "korunan yol" ile "yayınlanan yol"un ayrışmasına kapı açardı — bu modülün
yapabileceği en tehlikeli hata.

## Fail-closed — iki katman, arada boşluk yok

1. **Yetenek kapalıysa uç HİÇ VAR OLMAZ.** `zeus.ai.mcp.enabled` aynı zamanda yetenek anahtarı
   olduğundan `ZeusAutoConfigurationFilter` MCP autoconfig'lerinin tamamını veto eder;
   `RouterFunction` oluşmaz.
2. **Yetenek açık ama token yoksa AÇILIŞ REDDEDİLİR** (`IllegalStateException`).

> Token varlığına `@Conditional` koymak **YANLIŞ olurdu**: o durumda Spring AI'ın router
> function'ı yayında kalırken bizim filtremiz ve tool'larımız devre dışı kalır — korumasız,
> tool'suz bir uç. Bu yüzden kontrol koşul değil, **açılışı düşüren doğrulama**.

Çalışma zamanında reddedilen istek `401` + `application/problem+json` alır. **Eksik header ile
hatalı header birebir aynı gövdeyi** alır (oracle yok); ayrımı yalnız log tutar
(`anahtar yok` / `anahtar hatalı`). Karşılaştırma sabit zamanlıdır (`MessageDigest.isEqual`),
token ne loglanır ne yanıta girer.

## Sessiz SSE — `protocol` neden bir GÜVENLİK ayarı

**Ölçülen davranış** (Spring AI 2.0.1): `spring.ai.mcp.server.protocol` yazılmazsa çakışma
olmaz — **deprecated SSE taşıması yayınlanır**. Sebep: streamable autoconfig `STREAMABLE`'ı
açıkça ister (`matchIfMissing` yok), SSE ise `matchIfMissing=true` taşır.

| `protocol` | yayınlanan |
|---|---|
| yazılmamış | `webMvcSseServerRouterFunction` ← **istemediğimiz** |
| `STREAMABLE` | `webMvcStreamableServerRouterFunction` |
| `SSE` | `webMvcSseServerRouterFunction` |

Bu bir güvenlik meselesidir çünkü iki taşımanın ucu **ayrı property uzaylarından** gelir: SSE
`spring.ai.mcp.server.sse-endpoint`, streamable `...streamable-http.mcp-endpoint`. Filtre yolunu
streamable property'sinden türettiği için SSE aktifken **hiçbir şeyi korumaz**. Modülün kuralı
bu yüzden tek cümledir: **yalnız `STREAMABLE` desteklenir; `SSE` dâhil başka her değer açılışı
reddeder.** `McpTransportSeciminiKilitleyenTest` bu ölçümü dondurur; uçtan uca guard ayrıca
`/sse`'nin **404** döndüğünü iddia eder.

## Audit — neden `ToolCallback` dekoratörü

`AuditingToolCallback` her çağrıda tool adı, süre, sonuç ve **thread adını** loglar; hatayı
loglar ve **aynen yeniden fırlatır**. Correlation ID elle taşınmaz: `CorrelationIdFilter` kimliği
MDC'ye koyuyor, log pattern `%X{correlationId}` ile basıyor.

| Aday | Neden olmadı |
|---|---|
| Servlet filtresi | Gövde JSON-RPC zarfı; hangi tool'un hangi argümanla çağrıldığını bilmek için gövdeyi tüketip yeniden tamponlamak gerekir → streamable HTTP bozulur. Filtre yalnız "biri uca POST etti" diyebilir |
| MCP interceptor | `io.modelcontextprotocol.*` tiplerine bağlar — yığının en çok değişen katmanı; ayrıca yalnız protokol çağrısını görür, çözülmüş Java metodunu görmez |
| **Dekoratör (seçilen)** | Tool kimliği + argüman + süre + sonuç + thread'in **aynı anda kapsamda olduğu tek nokta**; tamamen `org.springframework.ai.tool.*` ile ifade edilir, app tarafında sıfır kod |

**`thread` alanı süs değil.** Tool gövdesinin istek thread'inde kalması Spring AI'ın
`immediateExecution(true)` ayarına dayanır — iç detay, sözleşme değil. Thread adını her satıra
yazmak, bir sürüm yükseltmesi bunu değiştirdiğinde durumu **üretim logunda** görünür kılar; aksi
hâlde yalnızca correlation-id sessizce boşalır. Uçtan uca guard da aynı iddiayı taşır.

**Kabul edilen kör nokta:** dekoratör yalnız `tools/call`'u görür. `initialize` ve `tools/list`
yalnız filtrenin log satırında kabaca görünür. Protokol seviyesi audit, SDK bağımlılığının
bedelini gerektirir — şimdi ödenmedi.

## Hata haritalaması — kasıtlı asimetri

Bu modülde **`@RestControllerAdvice` yoktur.** MCP yanıtları JSON-RPC'dir, REST kaynağı değil;
ProblemDetail dönen bir advice, iyi çerçevelenmiş bir tool hatasını istemcinin ayrıştıramadığı
bir protokol hatasına çevirirdi. Spring AI'ın `McpToolUtils`'i fırlatılan hatayı MCP'nin doğru
şekline (`CallToolResult(isError=true)`) zaten çevirir → **tool hataları audit'lenir ve aynen
yeniden fırlatılır.**

Modülün ürettiği tek ProblemDetail filtrenin `401`'idir; elle yazılır (filtre
`DispatcherServlet`'ten önce çalışır, hiçbir advice ona ulaşamaz) ve alan disiplinini
`ZeusAiExceptionHandler`'dan birebir alır.

Bir sonuç: bir tool'un *içinde* fırlatılan `ZeusAiException` artık 502 ProblemDetail değil **MCP
tool hatası** olarak yüzeye çıkar. Bu doğrudur; `zeus-ai`'nin 502 advice'ı uygulamanın kendi REST
uçları için yürürlüktedir.

## Yetenek sahipliği — SIRA KRİTİKTİR

`ZeusCapabilities.HEPSI`'nin **ilk** elemanı:

| Yetenek | Anahtar | Önek |
|---|---|---|
| **ai-mcp** | `zeus.ai.mcp.enabled` | `org.springframework.ai.mcp.` |
| ai | `zeus.ai.enabled` | `org.springframework.ai.` |

`sahipBul` `findFirst()` kullanır ve `ai` öneki MCP sınıflarını da kapsar. `ai` önce gelirse
`zeus.ai.mcp.enabled` **hiçbir şey yapmaz** ve MCP ucu, AI yeteneğini açan her uygulamada sessizce
yayına girer. **`test-autoconfig-sahipligi.sh` bu sırayı KORUMAZ** (geniş önek de sınıflandırır,
ters sırada da yeşil kalır); sıranın tek bekçisi `ZeusCapabilitiesSiralamaTest`'tir — ve dişliliği
sırayı ters çevirerek ölçülmüştür (3 testten 2'si kırmızıya döndü).

### Dört durumun sonucu

| Durum | Sonuç |
|---|---|
| `ai=true`, `mcp` yazılmamış, jar yok (**mevcut uygulamalar**) | Davranış değişikliği **sıfır**. Tek fark açılışta bir INFO satırı: `'ai-mcp' yeteneği KAPALI ... autoconfig veto edildi` — regresyon değildir |
| `ai` yazılmamış, `mcp=true` | Açılış düşer, ama bizim yazdığımız bir şeyden değil: `zeus-ai-mcp` → `zeus-ai` bağımlılığı yüzünden `ZeusAiAutoConfiguration` classpath'te ve verifier `zeus.ai.enabled` bildirimini talep eder. **"mcp, ai'yi gerektirir" diye ayrı bir kural GEREKMEZ** |
| `ai=false`, `mcp=true` | Çalışır — **birinci sınıf mod**: "yalnız tool" MCP sunucusu. Uygulama kendi LLM çağrısı yapmadan `@Tool` metotlarını dış ajanlara açar (ajan başka yerde, veri burada). Bu yüzden autoconfig `ChatClient`/`ZeusAiAssistant` **talep etmez**; testle kilitlenmiştir |
| Jar var, `mcp` yazılmamış | Verifier çelişki görür, açılış düşer. `=false` "jar var, yetenek kapalı" demenin yoludur |

## ⚠️ BİLİNEN BOŞLUK — property var, jar yok

> **`zeus.ai.mcp.enabled=true` yazılmış ama pom'da `zeus-ai-mcp` YOKSA**, çalışma zamanında
> hiçbir denetim bunu yakalayamaz: işaretçi sınıf WAR'da olmadığı için `ZeusCapabilityVerifier`
> sessiz kalır, ama yetenek açık olduğu için MCP autoconfig'leri veto edilmez ve `McpSchema`
> paylaşımlı module'den her uygulamaya görünür → **Spring AI'ın ham MCP sunucusu `/mcp`'de ayağa
> kalkar: bizim filtremiz, audit'imiz ve tool kaydımız olmadan.**

Runtime çözümü **yoktur**: sorunu tespit edecek modül, eksik olan modülün kendisidir. Bu asimetri
işaretçi-sınıf probe'unun doğasındadır. Denetim bu yüzden **build tarafında, fail-closed**:
`scripts/test-mcp-opt-in-butunlugu.sh` (dişliliği, bağımlılık geçici kaldırılarak ölçülmüştür).

## WildFly dağıtımı

İnce WAR kuralı aynen geçerli: `zeus-ai-mcp-*.jar` → **WAR içinde**; `spring-ai-*mcp*`,
`mcp-spring-webmvc`, `io.modelcontextprotocol.sdk:*` → **`com.zeus` module'ünde**.
`zeus-wildfly-module/pom.xml`'e `spring-ai-starter-mcp-server-webmvc` eklendi; module sözleşmesi
değiştiği için:

```bash
mvn clean install
./scripts/install-zeus-module.sh    # module yeniden üretilir + dışlama listeleri güncellenir
# ardından WildFly RESTART (main slot güncellendiğinde module tanımı cache'lidir)
```

**Ölçüm:** module **180 → 189 jar** (tam +9: `spring-ai-starter-mcp-server-webmvc`,
`spring-ai-autoconfigure-mcp-server-{common,webmvc}`, `spring-ai-mcp`, `spring-ai-mcp-annotations`,
`mcp-spring-webmvc`, `io.modelcontextprotocol.sdk:{mcp,mcp-core,mcp-json-jackson3}`). Kapanıştan
hiçbir şey düşmedi. Uygulama WAR'ı **109 KB**, içinde 7 zeus jar'ı.

### Tomcat çift kopya koruması

`spring-ai-starter-mcp-server-webmvc` → `spring-boot-starter-web` → `spring-boot-starter-tomcat`
zinciri `tomcat-embed-core`'u **compile scope'ta** getirir; o jar 146 `jakarta/servlet/**` sınıfı
taşır ve deployment classloader'ında servlet API'sinin ikinci kopyasını oluşturarak
`LinkageError` üretir. `zeus-ai-mcp/pom.xml`'deki `spring-boot-starter-tomcat` **`provided`**
bildirimi tüm alt ağacı kapanıştan düşürür — ölçüldü (kapanışta tomcat eşleşmesi: 0).
**O satır kaldırılamaz.**

### Bu ekleme sırasında ortaya çıkan platform arızası: `@WebServlet`

İlk deploy denemesi düştü:

```
NoSuchMethodException: io.modelcontextprotocol.server.transport
                       .HttpServletStatelessServerTransport.<init>()
```

`mcp-core-2.0.0.jar` **üç** sınıfını `@WebServlet(asyncSupported=true)` ile işaretliyor
(`HttpServletStatelessServerTransport`, `HttpServletStreamableServerTransportProvider`,
`HttpServletSseServerTransportProvider`); üçü de builder ile kurulur ve **no-arg ctor'u yoktur**.
Bizim kullandığımız taşıma bunlar değil (WebMVC `RouterFunction` tabanlı olan). `com.zeus`
`annotations="true"` ile import edildiği ve install script'i her jar'a Jandex index gömdüğü için
bu sınıflar deployment'ın composite index'ine girer ve WildFly onları servlet bildirimi sanıp
kurmaya çalışır.

**Kapsam denetimi bunu yakalayamaz:** `verify-module-coverage.sh` yeşildi, deploy düştü. Kural bir
kez daha doğrulandı — *kapsam denetimi "jar module'de var mı?"yı cevaplar, "sınıflar link olur /
kurulur mu?"yu cevaplayamaz.* (`jakarta.websocket` olayından sonra bu ailenin ikinci üyesi.)

**Jandex'i o jar için atlamak çözüm değildir:** `install-zeus-module.sh`'ın yorumu bunun ölçülmüş
arızasını kaydeder — kısmi index, hızlı yolu çalıştırır ama **tamamlanmamış** bir kompozit index
üretir ve `@HandlesTypes` taraması o jar'lardaki sınıfları **sessizce kaçırır**.

**Çözüm:** `zeus-war-defaults/descriptor-standard/web.xml` → `metadata-complete="true"`. Bu, aynı
descriptor'daki subsystem dışlamalarıyla (`weld`, `batch-jberet`, `jsf`, `jaxrs`) **aynı aileden**
bir önlemdir; servlet (undertow) subsystem'i dışlanamadığı için kol burada. İki ölçümle
desteklendi: Spring'in SCI'si etkilenmiyor (`@HandlesTypes` → `DispatcherServlet` kuruluyor), ve
ekosistemde `@WebServlet`/`@WebFilter`/`@WebListener` kullanımı **sıfır** (zeus filtreleri
`@Order`'lı Spring bean'leri). Şablon **yalnız standart tip** içindir; SOAP tipi `@WebService`
endpoint yayınladığı için aynı kol ölçülmeden çekilmemelidir.

## Testler ve guard'lar

`zeus-ai-mcp/src/test` — 32 test:

| Sınıf | Neyi kilitliyor |
|---|---|
| `ZeusMcpAutoConfigurationTest` | Opt-in simetri kilidi (`=false` hayatta kalır); token'sız/protocol'süz/uçsuz/`SSE` açılışın reddi; kayıt bean'i yoksa provider oluşmaması; varsa her callback'in audit dekoratörüyle sarılı olması; `ai=false + mcp=true` modu; servlet dışı ortamda yüklenmeme |
| `McpKopruOlcumuTest` | **Tripwire**: `@Tool`/`@ToolParam` → MCP şeması köprüsü. Spring AI yükseltmesinde köprü şekli değişirse burası kırmızıya döner |
| `McpTransportSeciminiKilitleyenTest` | Ölçülmüş taşıma davranışı (sessiz SSE) |
| `ZeusMcpToolsTest` | Kayıt doğrulamaları |
| `ZeusMcpTokenFilterTest` | Oracle yokluğu, token sızdırmama, yol kapsamı |
| `AuditingToolCallbackTest` | Şeffaflık + **yutmama** |

`zeus-base`: `ZeusCapabilitiesSiralamaTest` (önek sırası).

Guard'lar (`scripts/run-guards.sh`):
- `test-mcp-opt-in-butunlugu.sh` (`app-std`) — yukarıdaki **bilinen boşluğun** tek savunması.
- `test-mcp-uctan-uca.sh` (`app-std+wf`) — gerçek protokol: fail-closed, `/sse` 404,
  `tools/list` + `@ToolParam`, `tools/call`, correlation-id ve **thread** iddiaları.
- `test-war-packaging.sh` — WAR'da 8 zeus jar'ı; MCP SDK ve `spring-ai-*` WAR'a girmemeli.

## Güvenlik notları

- **Sır repoda tutulmaz**: `zeus.ai.mcp.token=${ZEUS_MCP_TOKEN:}`. Rotasyon = ortam değişkenini
  değiştir + yeniden başlat. Build guard'ı düz metin sırrı reddeder.
- **Yalnız okuma yapan tool yayınlayın.** Yazma yapan bir tool açılacaksa yetki kontrolü tool
  metodunun **içinde** olmalıdır; modelin kararına bırakılmaz.
- Her `tools/call` audit'lenir (tool adı, süre, sonuç, thread) ve correlation-id taşır.

### Bilinen boşluk: kimlik ve yetkilendirme

v1'in koruması **bir kimlik değil, taşıyıcı yetkidir**: tüm çağıranlar aynı principal'dır, tool
bazlı yetki yoktur, iptal = sır rotasyonu + restart, audit *kimin* çağırdığını yazmaz. Ayrıca
protokol hata gövdeleri Spring AI transport'u tarafından `stackTrace` ile serileştirilir (sınıf
adları, dosya/satır, `classLoaderName`) — bizim `401`'imiz temizdir, sızdıran taraf SDK'dır; uç
sırla korunduğu için v1'de kabul edilmiştir.

Gerçek kimlik gelecek **`zeus-security`** modülünün işidir. Dikişler yerinde: `ZeusMcpTokenFilter`
tek bean'dir (OAuth2 resource-server filtresine takas edilir), `ZeusMcpAuditor` bir `principal`
alanı kazanır, `AuditingToolCallback` tool bazlı yetkinin doğal yeridir.

Sunumun (`sunum/mcp-server-stratejisi.pptx`) yönetime verdiği dört kabul koşulundan ikisi — metot
seviyesinde `@PreAuthorize` ve son kullanıcı token'ının taşınması — **v1'de karşılanmamıştır**;
sunumun kendi dürüstlük notu ("zeus-fw'da bugün JWT/Security modülü YOK") bu durumu zaten
kaydetmişti.

## Tarihsel karar (2026-10-03) — MCP neden `zeus-ai` İÇİNDE değil

`sunum/mcp-server-stratejisi.pptx` slayt 4, MCP'yi `zeus-ai`'nin içine koymayı öneriyordu:
*"Uygulama ekibinin işi üç adıma iner: 1. `zeus-ai` bağımlılığını ekler 2. Servis metodunu
`@McpTool` ile işaretler 3. Deploy eder."* Teknik olarak da ucuzdu: MCP autoconfig'lerinin tamamı
`org.springframework.ai.` altında olduğu için mevcut `ai` yeteneği onları zaten sahiplenirdi ve
`ZeusCapabilities`'e hiç dokunulmazdı.

**Ayrı modül seçildi.** Gerekçe: MCP bir **ağ ucu yayınlar** ve bu "LLM'e soru sormak istiyorum"
demekle aynı opt-in olmamalıdır. `zeus-ai` içinde olsaydı AI kullanan her uygulama MCP server
jar'larını taşır ve ucu kapatmak için ikinci bir property öğrenmek gerekirdi — yeteneğin
"uygulamanın öğrenmesi gereken tek kavram olsun" ilkesini tersine çevirirdi.

Sunumun `@McpTool` önerisi de uygulanmadı: mevcut `@Tool` yeniden kullanıldı, böylece uygulama
koduna ikinci bir Spring AI import istisnası açılmadı ve tool bir kez yazılıp iki tüketiciye
(süreç içi assistant + MCP) hizmet ediyor. **Sunum bu iki noktada güncellenmelidir.**

## Sırada ne var

- **MCP client** (roadmap P2 / 2027-Q1): uygulamaların dış MCP sunucularını tüketmesi. Not:
  `org.springframework.ai.mcp.` öneki client autoconfig'lerini de sahiplenecek; o faz geldiğinde
  muhtemelen daha dar bir `org.springframework.ai.mcp.client.` satırı `ai-mcp`'den **önce**
  eklenecek — aynı sıralama disiplini bir kat daha derin.
- **MCP Resource / Prompt**: v1 yalnız Tool.
- **Agent harness'ı** (deepagents karşılığı): ayrı tasarım turu; MCP tool yüzeyi onun altyapısı.
- **`zeus-security`**: yukarıdaki bilinen boşluk.
