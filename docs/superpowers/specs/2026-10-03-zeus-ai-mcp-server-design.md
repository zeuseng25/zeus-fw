# zeus-ai-mcp: uygulama servislerini MCP tool olarak yayınlamak

**Tarih:** 2026-10-03 · **Durum:** tasarım, onay bekliyor · **Kapsam:** v1 = yalnız MCP **server**

## Problem

Zeus uygulamalarının yetenekleri bugün yalnızca **süreç içinde** modele açılabiliyor. Uygulama
`@Tool` anotasyonlu bir nesneyi `ZeusAiAssistant.ask(...)`'a varargs olarak veriyor, tool döngüsünü
Spring AI'ın `ToolCallingAdvisor`'ı framework'ün kendi LLM çağrısı içinde yürütüyor:

```java
// spring-wildfly-arch — ProductAiServiceImpl.askCatalog
return ai.ask(question, productAiTools);
```

Bu modelde **modeli uygulama seçer**. Kurumsal chat uygulaması ya da Claude gibi *dışarıdaki* bir
asistanın aynı metotları çağırmasının yolu yok. Her ekip bunu kendi başına çözmeye kalkarsa sonuç,
roadmap'in kendi risk satırında yazdığı şey olur: *"AI ajan entegrasyonu her uygulamada ad-hoc
çözülür; standart platform yeteneği oluşmaz."*

MCP (Model Context Protocol) bu boşluğun standardı: JSON-RPC 2.0 üzerine kurulu, ağda HTTP ile
taşınan, asistan ile sistemler arasında ortak dil. Bugün takılı olan uç chat uygulaması
(**MCP client**); boşta olan uç iş uygulamalarımız (**MCP server**). Kurulacak olan bu.

**Neden şimdi:** Spring AI 2.0 ile MCP kütüphanenin çekirdeğine girdi — `@McpTool`/`@McpResource`,
WebMVC/WebFlux transport'ları, streamable HTTP varsayılan, resmî Java SDK 2.0 (2025-11-25
spesifikasyonu). 1.x satırında MCP toplulukta ve spesifikasyonun eski sürümündeydi. Stack'imiz
(Boot 4.0.7 / Spring AI 2.0.1) zaten hizalı.

### Bu dokümanın kapsamı DEĞİL: agent harness'ı

İş, LangChain'in `deepagents` reposu referans alınarak başlatıldı. Keşif iki şeyi ayırdı:

1. **deepagents'ın SDK'sında MCP server kodu yok.** MCP yalnızca `libs/code` CLI'ında, *client*
   tarafında var. "API'leri MCP'ye çevirme" ihtiyacının karşılığı deepagents değil, Spring AI'ın
   kendi MCP desteği.
2. **deepagents'ın asıl katkısı bir agent harness'ı**: sanal dosya sistemi (8 tool), subagent
   delegasyonu (`task`), context offload/compaction, permission+interrupt katmanı, model başına
   harness profilleri. Üç şey dikkat çekici: varsayılan sistem promptu **boş** (davranış promptta
   değil tool açıklamalarında yaşıyor), todo/planlama tool'u 0.7.0'da varsayılandan **çıkarılmış**,
   ve SDK ~27k satır olup LangGraph'ın state channel / checkpointer / `interrupt` primitiflerine —
   yer yer private API'lerine — bağlı. Spring AI'da bunların runtime karşılığı yok: Advisor zinciri
   model çağrısını sarar, ama durum/checkpoint/interrupt graph'ı yoktur.

Dolayısıyla agent harness'ı "port" değil **yeniden tasarım** ve ayrı bir tasarım turunun konusu.
MCP tool yüzeyi onun da altyapısı olacağı için sıra bu: önce yetenekler protokolde konuşulabilir
hâle gelir, sonra o yetenekleri kullanan bir ajan katmanı tartışılır.

## Kararlar (kullanıcı, 2026-10-03)

1. **Sıralama: önce MCP, agent sonra.** İki yarı tek spec'e sığmıyor; MCP yarısı roadmap F5 ile
   birebir örtüşüyor ve ölçülebilir bir kabul kriteri var.
2. **Ayrı modül: `zeus-ai-mcp`**, `zeus-ai`'ye bağımlı, kendi `zeus.ai.mcp.enabled` anahtarıyla.
   Gerekçe: MCP bir **ağ ucu yayınlar**; bu "LLM'e soru sormak istiyorum" demekle aynı opt-in
   olmamalı. *(Sunum `zeus-ai` içini öneriyordu — bkz. "Değerlendirilen alternatifler".)*
3. **Tool bildirimi mevcut `@Tool`/`@ToolParam` ile.** Framework bunları MCP'ye köprüler. Yeni
   anotasyon icat edilmez, uygulama koduna ikinci bir Spring AI import istisnası açılmaz.
4. **Yayın yüzeyi açık kayıt bean'i ile** (`ZeusMcpTools`). Classpath taramasıyla sessiz yayın yok.
5. **v1 erişim denetimi: paylaşılan sır + fail-closed + audit.** Gerçek kimlik ve tool bazlı yetki
   gelecek `zeus-security` modülüne bırakılır ve **bilinen boşluk** olarak yazılır.
6. **Transport: streamable HTTP / WebMVC**, uç `/api/mcp`. WebFlux kasıtlı olarak kapsam dışı.
7. **Kanıt yüzeyi salt okunur.** Yalnız paylaşılan sırla korunan bir uçtan veri değiştirilmez.

## Ölçülen gerçekler

Aşağıdaki maddeler **ölçülmüştür** (2026-10-03, Spring AI 2.0.1, JDK 25; ölçüm raporu:
`.superpowers/sdd/2026-10-03-zeus-ai-mcp-server/task-0-report.md`). "Beklenti" diye işaretli olanlar
henüz ölçülmedi.

- **✅ BOM 2.0.1 çözüyor.** `zeus-dependencies`'i import eden bir probe pom'unda
  `spring-ai-starter-mcp-server-webmvc` ve tüm `org.springframework.ai:*mcp*` → **2.0.1**.
  `io.modelcontextprotocol.sdk:{mcp,mcp-core,mcp-json-jackson3}` → **2.0.0**, transitif ve
  pinlenmemiş (BOM kuralı 4 ile tutarlı; `spring-ai-bom` bu groupId'yi yönetmiyor).
- **✅ Köprü çalışıyor, bean tipi `ToolCallbackProvider`.** 2.0.1 bytecode'unda imza:
  `syncTools(ObjectProvider<List<ToolCallback>>, List<ToolCallback>, ObjectProvider<List<ToolCallbackProvider>>, ObjectProvider<ToolCallbackProvider>, McpServerProperties)`
  → `List<McpServerFeatures$SyncToolSpecification>`. `ApplicationContextRunner` ölçümü:
  `MethodToolCallbackProvider` ile sarılmış iki `@Tool` metodu → `Registered tools: 2`, tool adları
  metot adları, **`@ToolParam` açıklaması input şemasında görünüyor**, tek `McpSyncServer` bean'i,
  ve boş `List<ToolCallback>` parametresi context'i düşürmüyor. **Yani `@Tool` → MCP şeması
  dönüşümü için kod yazmıyoruz; yeni anotasyon da gerekmiyor.**
  Provider bean'i yoksa context ayakta kalıyor ve sunucu sıfır tool ile açılıyor.
- **⚠️ ÖLÇÜM VARSAYIMI ÇÜRÜTTÜ — `protocol` yazılmazsa çakışma değil, SESSİZ SSE olur.**
  Beklenti "iki transport provider → `NoUniqueBeanDefinitionException`" idi. Ölçüm: her üç durumda
  da **tam bir** router function var —

  | `spring.ai.mcp.server.protocol` | Yayınlanan |
  |---|---|
  | yazılmamış | `webMvcSseServerRouterFunction` |
  | `STREAMABLE` | `webMvcStreamableServerRouterFunction` |
  | `SSE` | `webMvcSseServerRouterFunction` |

  Sebep: streamable autoconfig `protocol=STREAMABLE`'ı açıkça istiyor (`matchIfMissing` yok), SSE
  ise `matchIfMissing=true`. Yani **varsayılan SSE'dir** — sunumun "kurumsal proxy ve yük
  dengeleyicilerle iyi geçinmiyor" dediği, bu sürümde `@Deprecated(forRemoval=true)` olan transport.
  **Ve bu bir güvenlik meselesi:** iki transport'un ucu ayrı property uzaylarından gelir —
  SSE `spring.ai.mcp.server.sse-endpoint` (+ `sse-message-endpoint`), streamable
  `spring.ai.mcp.server.streamable-http.mcp-endpoint`. Token filtresi yolunu streamable
  property'sinden türettiği için, SSE aktifken **hiçbir şeyi korumaz**.
  → `protocol` kontrolü bu yüzden hijyen değil, **doğruluk ve güvenlik kontrolü**; ve modülün
  kuralı tek cümleye iniyor: **zeus-ai-mcp yalnız `STREAMABLE` destekler, başka her değer açılışı
  reddeder.**
- **Uç varsayılanı `/mcp`**, `/api/mcp` değil → açıkça yazılmalı.
- **✅ Tomcat budaması çalışıyor (ölçüldü).** `spring-ai-starter-mcp-server-webmvc`
  `spring-boot-starter-web:4.0.7`'yi (Boot BOM, starter'ın pom'undaki 4.1.0'ı eziyor) ve onun
  üzerinden **`tomcat-embed-core:11.0.22`'yi compile scope'ta** getiriyor — tehlike gerçek.
  Aynı pom'a `spring-boot-starter-tomcat` `provided` eklendiğinde runtime kapanışında
  **tomcat eşleşmesi sıfır**. Bu yüzden `provided` bildirimi zorunlu ve kaldırılamaz.
- **İstek thread'i için güçlü ön bulgu (henüz ölçülmedi):** `McpServerAutoConfiguration` servlet
  ortamında `immediateExecution(true)` uyguluyor → tool gövdesi istek thread'inde kalmalı, MDC'deki
  correlation-id hayatta kalmalı. Spring AI **iç detayı**, sözleşme değil → P2'de ölçülecek.
- **✅ 11 MCP autoconfig sınıfının tamamı `org.springframework.ai.mcp.server.` altında**
  (jar'ların `AutoConfiguration.imports` dosyalarından okundu) → `org.springframework.ai.mcp.`
  öneki hepsini sahipleniyor.
- **✅ Module'e TAM 9 jar giriyor** (gerçek `zeus-wildfly-module` kapanışında ölçüldü, Artım C):
  `spring-ai-starter-mcp-server-webmvc`, `spring-ai-autoconfigure-mcp-server-{common,webmvc}`,
  `spring-ai-mcp`, `spring-ai-mcp-annotations`, `mcp-spring-webmvc`,
  `io.modelcontextprotocol.sdk:{mcp, mcp-core, mcp-json-jackson3}`. Kapanıştan **hiçbir şey
  düşmüyor** ve `tomcat-embed-core`/`-websocket` eşleşmesi **sıfır**.
  *Düzeltme:* Artım A'da "~11 jar + aynı artifactId/farklı groupId keskin kenarı" denmişti
  (`snakeyaml-engine`, Jackson 3 `jackson-dataformat-yaml`). O sonuç **izole probe pom'unun
  yapaylığıydı**: orada `json-schema-validator` 3.0.0'a çözülüp Jackson 3 yaml hattını çekiyordu.
  Gerçek kapanışta Maven mediation **3.0.1**'i seçiyor, yaml yalnız Jackson 2 hattından
  (`com.fasterxml...:2.21.4`) geliyor, `tools.jackson` yalnızca zaten mevcut `core`/`databind`
  olarak var. Yani o keskin kenar **bu vakada oluşmuyor.** Altındaki genel gözlem yine doğru ve
  kayda değer: dışlama listeleri zorunlu olarak **artifactId ile** eşleşir (`packagingExcludes`
  jar dosya adına bakar, dosya adı groupId taşımaz), dolayısıyla ileride aynı artifactId'yi iki
  groupId ile taşıyan bir çift gerçekten girerse ancak module **iki hattı da** taşıdığı sürece
  güvenli olur.
- **`spring-ai-starter-mcp-server-common` 2.0.1'de yok** (1.1.2'de vardı); `-autoconfigure-`
  karşılığı var ve `-webmvc`'nin transitifi olarak geliyor.
- **`install-zeus-module.sh` değişikliği gerekmiyor:** `EXCLUDE_REGEX` `zeus-[a-z0-9-]+` içeriyor →
  `zeus-ai-mcp` jar'ı module'e girmez, WAR'da taşınır. *(App repo'sundaki `gelistirmeler/13-...`
  bu regex'i `zeus-(base|logger|database|service|redis|batch)` diye yazıyor — **bayat**.)*
- **⚠️ "Descriptor değişikliği gerekmiyor" ÇÜRÜTÜLDÜ — `web.xml` şablonu eklendi.**
  Beklentinin yarısı doğruydu: WebMVC streamable transport gerçekten bir `RouterFunction`, mevcut
  `DispatcherServlet` üzerinde taşınıyor ve `jboss-deployment-structure.xml`'e dokunulmadı. Ama
  `mcp-core-2.0.0.jar` **üç sınıfını `@WebServlet(asyncSupported=true)` ile işaretliyor**
  (`HttpServletStatelessServerTransport`, `HttpServletStreamableServerTransportProvider`,
  `HttpServletSseServerTransportProvider`) ve üçünün de **no-arg ctor'u yok** — builder ile
  kurulurlar, bizim kullandığımız taşıma bunlar değil. `com.zeus` `annotations="true"` ile import
  edildiği ve install script'i her jar'a Jandex index gömdüğü için bu sınıflar deployment'ın
  composite index'ine giriyor, WildFly onları servlet sanıp kuruyor, deploy düşüyor:
  `NoSuchMethodException: io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport.<init>()`
  **Kapsam denetimi bunu yakalayamadı** (yeşil `verify-module-coverage.sh` + düşen deploy ile
  ölçüldü) — dokümanın "jar var mı ≠ sınıflar link olur mu" kuralının ikinci kanıtı.
  Çözüm: `zeus-war-defaults/descriptor-standard/web.xml` → `metadata-complete="true"`. Bu, aynı
  descriptor'daki subsystem dışlamalarıyla (CDI/batch/JSF/JAX-RS) **aynı aileden** bir önlemdir;
  servlet (undertow) subsystem'i dışlanamadığı için kol burada. Jandex'i o jar için atlamak çözüm
  DEĞİL: install script'inin yorumu, kısmi index'in `@HandlesTypes` taramasını sessizce bozduğunu
  ölçülmüş arıza olarak kaydediyor.
  Yan etkisizlik ölçüldü: Spring'in SCI'si etkilenmiyor (`metadata-complete` eklendikten sonra
  deploy servlet aşamasını geçti ve `DispatcherServlet` kuruldu), ekosistemde
  `@WebServlet`/`@WebFilter`/`@WebListener` kullanımı **sıfır** (zeus filtreleri `@Order`'lı Spring
  bean'leri). Şablon **yalnız standart tip** için eklendi — SOAP tipi `@WebService` endpoint
  yayınladığı için aynı kol ölçülmeden çekilmemeli.
- **Guard isimlendirmeyi kısıtlıyor:** `test-autoconfig-sahipligi.sh` işaretçi sınıf adından dosya
  yolu türetiyor (`zeus-<ad>/src/main/java/<paket>/<Sınıf>.java`) → yetenek adı **`ai-mcp`**,
  işaretçi sınıf **`com.zeus.framework.ai.mcp.ZeusMcpAutoConfiguration`** olmak zorunda.
- **✅ `install-zeus-module.sh` değişikliği gerekmedi** (doğrulandı): `EXCLUDE_REGEX` bugün
  `zeus-[a-z0-9-]+` içeriyor → `zeus-ai-mcp` jar'ı module'e girmedi, WAR'da taşındı; module.xml'in
  jakarta export listesine de ekleme gerekmedi. *(App repo'sundaki `gelistirmeler/13-...` bu
  regex'i `zeus-(base|logger|database|service|redis|batch)` diye yazıyor — **bayat**, düzeltilecek.)*
- **✅ Uçtan uca doğrulandı** (WildFly 41 + Oracle, 2026-10-03): deploy başarılı, link hatası yok,
  `tools/list` iki tool'u `@ToolParam` açıklamasıyla döndü, `tools/call` Oracle'dan gerçek veri
  getirdi, audit satırı istek thread'inde (`thread=default task-3`) ve istekle aynı
  correlation-id'yi taşıdı. Ayrıntı: `.superpowers/sdd/2026-10-03-zeus-ai-mcp-server/task-2-report.md`.

### Bilinen eksikler (dokümana açıkça yazılacak)

- Metot seviyesinde yetkilendirme (`@PreAuthorize`) yok — Spring Security zeus-fw'da mevcut değil.
- Son kullanıcı token'ının taşınması yok. Sunumun yönetime verdiği 4 kabul koşulundan bu ikisi
  v1'de **karşılanmıyor** (sunumun kendi notu: *"zeus-fw'da bugün JWT/Security modülü YOK"*).
- Paylaşılan sır bir **kimlik değil, taşıyıcı yetki**: tüm çağıranlar aynı principal, tool bazlı
  yetki yok, iptal = sır rotasyonu + restart.
- Roadmap'in "AI ajanından CRUD" kriteri v1'de kısmen karşılanıyor (yalnız okuma).
- Audit yalnız `tools/call`'u kapsar; `initialize`/`tools/list` yalnız filtre log satırında görünür.
- **Protokol hata gövdeleri iç detay sızdırır** (R9, ölçüldü): Spring AI'ın transport'u istisnayı
  `stackTrace` alanıyla (sınıf adları, dosya/satır, `classLoaderName`) JSON'a serileştiriyor.
  Bizim ürettiğimiz 401 temiz; sızdıran taraf SDK/transport. Uç paylaşılan sırla korunduğu için
  v1'de kabul edildi.

## Mimari

### 1. Yayın sözleşmesi — `ZeusMcpTools`

Bir nesnenin MCP'ye ulaşmasının **tek** yolu. Classpath taraması, `@Component` süpürmesi, işaret
anotasyonu yok.

```java
@Configuration
public class McpConfig {
    @Bean
    ZeusMcpTools zeusMcpTools(ProductAiTools productAiTools) {
        return ZeusMcpTools.of(productAiTools);
    }
}
```

`of(Object... toolObjects)` + `none()`. `of` içinde `MethodToolCallbackProvider` **hemen** kurulur;
böylece `@Tool` metodu olmayan bir nesne ve çift tool adı **bean kuruluşunda** patlar — Spring
AI'ın kendi doğrulamalarından bedavaya gelen fail-fast. Tip olması (ham `List<Object>` değil)
`@ConditionalOnBean(ZeusMcpTools.class)`'ı ve grep'lenebilirliği mümkün kılıyor.

**Neden açık kayıt:** `@Tool`'u `ask()`'e vermek süreç içi ve çağrı başına seçilir; MCP aynı metodu
ağ üzerinden dış ajanlara açar. "Classpath'teki tüm `@Tool` bean'lerini yayınla" kuralında, yarın
biri yazma yapan bir `@Tool` eklediğinde o da kendiliğinden dışarı çıkar. `ZeusAiAssistant`'ın açık
varargs üslubuyla da aynı çizgi: yayın yüzeyi tek dosyada review edilir.

### 2. Köprü — `ZeusMcpAutoConfiguration`

```java
@AutoConfiguration(afterName = {
        "org.springframework.ai.mcp.server.common.autoconfigure.McpServerJsonMapperAutoConfiguration",
        "org.springframework.ai.mcp.server.webmvc.autoconfigure.McpServerStreamableHttpWebMvcAutoConfiguration"
})
@ConditionalOnClass(ToolCallbackProvider.class)
@ConditionalOnWebApplication(type = Type.SERVLET)
@ConditionalOnProperty(prefix = "zeus.ai.mcp", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(ZeusMcpProperties.class)
public class ZeusMcpAutoConfiguration { … }
```

`afterName`'in **dize** olması zorunlu — `zeus-ai`'deki aynı gerekçeyle: framework Spring AI'ın
`*.autoconfigure` paketlerine derleme zamanı bağımlılığı **kurmaz**. `@ConditionalOnClass` de
`io.modelcontextprotocol.spec.McpSchema` değil `org.springframework.ai.tool.ToolCallbackProvider`'ı
gösterir (`spring-ai-model`'deki kararlı genel API) — koşul yığının en çok değişen katmanına
binmesin.

`matchIfMissing` **yok**: yetenek opt-in'i gereği yazılmadıkça kapalı, ve **simetri kuralı** gereği
anahtar, veto ettiği 3. parti yığınla aynı anahtar.

Ürettiği bean, `ZeusMcpTools`'un nesnelerinden `ToolCallback`'leri çıkarıp her birini audit
dekoratörüyle sarar ve tek bir `ToolCallbackProvider` olarak verir. `@ConditionalOnBean(ZeusMcpTools.class)`
olduğu için, yetenek açık ama kayıt bean'i yoksa MCP sunucusu **sıfır tool ile** açılır — güvenli
ve gözlenebilir bir durum (bir kez WARN loglanır).

### 3. Fail-closed güvenlik

İki katman, ve ikincisi birincisinin yerini **almaz**:

- **Yetenek kapalıysa uç hiç var olmaz.** `zeus.ai.mcp.enabled` aynı zamanda yetenek anahtarı
  olduğu için `ZeusAutoConfigurationFilter` Spring AI'ın **tüm** MCP autoconfig yığınını veto eder;
  `RouterFunction` oluşmaz.
- **Yetenek açık ama token yoksa açılış reddedilir.** `ZeusMcpAutoConfiguration` ctor'unda
  `IllegalStateException` (`ZeusCapabilityVerifier` ve `ZeusAutoConfigurationFilter`'ın açılış
  hatası üslubuyla aynı).

> **Token varlığına `@Conditional` koymak YANLIŞ olur.** O durumda Spring AI'ın router function'ı
> yayında kalırken bizim filtremiz ve tool'larımız devre dışı kalır — mümkün olan en kötü sonuç.
> Bu yüzden kontrol koşul değil, **açılışı düşüren doğrulama**.

Üç fail-fast, hepsi ctor'da:

1. **Token boş mu** → `IllegalStateException`.
2. **`spring.ai.mcp.server.protocol` tam olarak `STREAMABLE` mi, ve
   `...streamable-http.mcp-endpoint` yazılmış mı** → değilse `IllegalStateException`.
   Bu kontrol ölçümle **güvenlik kontrolüne** dönüştü: property yazılmazsa Spring AI sessizce
   deprecated **SSE** transport'unu yayınlıyor, SSE'nin ucu ise farklı bir property uzayından
   (`sse-endpoint`) geliyor → token filtresi onu **korumaz**. Dolayısıyla modülün kuralı:
   *zeus-ai-mcp yalnız `STREAMABLE` destekler; `SSE` dâhil başka her değer açılışı reddeder.*
   Hata metni yazılması gereken iki satırı birebir söyler.
3. **Tek INFO satırı** — token asla loglanmaz.

`ZeusMcpTokenFilter` bir `OncePerRequestFilter`, `@Order(HIGHEST_PRECEDENCE + 20)`: correlation-id
ve request-log filtrelerinden **sonra** çalışır, böylece reddedilen çağrı da correlation-id alır ve
istek logunda görünür. İki tasarım detayı taşıyıcı:

- **Yol, transport'un okuduğu property'den türetilir.** `shouldNotFilter` aynı
  `spring.ai.mcp.server.streamable-http.mcp-endpoint` değerini okur. Bu yüzden `zeus.ai.mcp`
  altında **uç yolu property'si yoktur**: korunan yol ile yayınlanan yolun ayrışması bu modülün
  yapabileceği en tehlikeli hata olurdu, ve tek kaynak kuralıyla yapısal olarak imkânsız kılınıyor.
- Karşılaştırma `MessageDigest.isEqual` (sabit zamanlı). Red `401` + `application/problem+json`;
  **eksik ve hatalı token birebir aynı gövdeyi** alır (oracle yok), ayrımı yalnız log tutar.

### 4. Audit — `ToolCallback` dekoratörü

`AuditingToolCallback` her çağrıda tool adı, süre, sonuç ve **thread adını** loglar; hatayı loglar
ve **aynen yeniden fırlatır**. Correlation-id MDC'den bedavaya geliyor (`CorrelationIdFilter` zaten
koyuyor, log pattern `%X{correlationId}` içeriyor) → zeus-logger'a bağımlılık yok.

**Neden dekoratör, neden filtre/interceptor değil:**

| Seçenek | Neden olmadı |
|---|---|
| Servlet filtresi | Gövde JSON-RPC zarfı; hangi tool'un ne argümanla çağrıldığını bilmek için gövdeyi tüketip yeniden tamponlamak gerekir — streamable HTTP'yi bozar ve SDK'nın çerçevelemesini kopyalar. Filtre yalnız "biri `/api/mcp`'ye POST etti" diyebilir |
| MCP interceptor / `McpSyncServerCustomizer` | Bizi `io.modelcontextprotocol.*` SDK tiplerine bağlar — yığının en çok değişen katmanı ve `afterName`-string disiplininin var olma sebebi. Ayrıca yalnız protokol çağrısını görür, çözülmüş Java metodunu görmez |
| **`ToolCallback` dekoratörü** | Tool kimliği + argüman + süre + sonuç + thread'in **aynı anda kapsamda olduğu tek nokta**, ve tamamen `org.springframework.ai.tool.*` ile ifade ediliyor. Uygulamanın ne kaydettiyse onu sarar, app tarafında sıfır kod |

**Kabul edilen kör nokta:** dekoratör `tools/list`, `initialize` ve `tools/call` dışı MCP
metotlarını görmez. Onlar yalnız filtrenin log satırında (metot + durum + correlation-id) kabaca
görünür. Protokol seviyesi audit gerekirse SDK bağımlılığının bedeli o zaman ödenir, şimdi değil.

### 5. Hata haritalaması — kasıtlı asimetri

Bu modülde **`@RestControllerAdvice` yok**, ve bu bilinçli: MCP yanıtları JSON-RPC, REST kaynağı
değil. ProblemDetail dönen bir advice, iyi çerçevelenmiş bir tool hatasını istemcinin
ayrıştıramadığı bir protokol hatasına çevirirdi. Spring AI'ın `McpToolUtils`'i `ToolCallback.call`'dan
fırlatılan hatayı `CallToolResult(isError=true)`'ya — MCP'nin doğru şekline — zaten çeviriyor.
Dolayısıyla **tool gövdesi hataları audit'lenir ve aynen yeniden fırlatılır**.

Modülün ürettiği tek ProblemDetail filtrenin `401`'i; elle yazılır (filtre `DispatcherServlet`'ten
önce çalışır, hiçbir advice ona ulaşamaz) ve `title`/`detail`/iç detay sızdırmama disiplinini
`ZeusAiExceptionHandler`'dan birebir alır. Tutarlılık orada yaşıyor.

Bir sonuç yazılmalı: bir tool'un *içinde* fırlatılan `ZeusAiException` artık 502 ProblemDetail
değil MCP tool hatası olarak yüzeye çıkar. Bu doğru; `zeus-ai`'nin 502 advice'ı uygulamanın kendi
REST uçları için yürürlükte kalır.

### 6. Yetenek sahipliği ve sıralama

`ZeusCapabilities.HEPSI`'nin **ilk** elemanı olarak:

| Yetenek | Anahtar | İşaretçi sınıf | Önek |
|---|---|---|---|
| **ai-mcp** | `zeus.ai.mcp.enabled` | `com.zeus.framework.ai.mcp.ZeusMcpAutoConfiguration` | `org.springframework.ai.mcp.` |
| ai | `zeus.ai.enabled` | `com.zeus.framework.ai.ZeusAiAutoConfiguration` | `org.springframework.ai.` |

**Sıra kritik.** `sahipBul` `findFirst()` kullanıyor ve mevcut `ai` öneki MCP sınıflarını da
kapsıyor. `ai` önce gelirse `zeus.ai.mcp.enabled` **hiçbir şey yapmaz** ve MCP ucu AI açan her
uygulamada sessizce yayına girer. `test-autoconfig-sahipligi.sh` bu sırayı **korumaz** (geniş önek
de sınıflandırdığı için satırlar ters olsa da yeşil kalır) → sıralamayı koruyan tek şey yeni bir
birim test olacak.

Dört durumun sonucu:

| Durum | Sonuç |
|---|---|
| `ai=true`, `mcp` yazılmamış, jar yok (**bugünkü tüm uygulamalar**) | İşaretçi sınıf yüklenemez → çelişki yok, hata yok. MCP autoconfig'leri veto edilir. **Davranış değişikliği sıfır**; tek fark açılışta bir INFO satırı (`'ai-mcp' yeteneği KAPALI …`). Dokümana yazılacak ki regresyon sanılmasın |
| `ai` yazılmamış, `mcp=true` | Açılış düşer — ama bizim yazdığımız bir şeyden değil: `zeus-ai-mcp` `zeus-ai`'ye bağımlı olduğu için `ZeusAiAutoConfiguration` classpath'te ve verifier `zeus.ai.enabled`'ın yazılmadığını söyler. **"mcp, ai'yi gerektirir" diye yeni bir kural gerekmiyor** |
| `ai=false`, `mcp=true` | Çalışır, ve **birinci sınıf mod olarak desteklenecek**: "yalnız tool" MCP sunucusu — uygulama kendi LLM çağrısı yapmadan `@Tool` metotlarını dış ajanlara açar (ajan başka yerde, veri burada). Bu yüzden `ZeusMcpAutoConfiguration` `ChatClient`/`ZeusAiAssistant` bean'i **talep etmemeli** |
| Jar var, `mcp` yazılmamış | Verifier çelişki görür, açılış düşer. `=false` "jar var, yetenek kapalı" demenin yolu |

## Hata yönetimi: çalışma zamanı açık, build kapalı

Mevcut desenle aynı. Ama **bir boşluk var ve runtime'da kapatılamıyor:**

> **`zeus.ai.mcp.enabled=true` yazılmış ama pom'da `zeus-ai-mcp` YOKSA:** verifier yakalayamaz
> (işaretçi sınıf yok), filtre MCP autoconfig'lerini geçirir, `McpSchema` paylaşımlı module'den
> her uygulamaya görünür → **Spring AI ham, tool'suz, kimlik denetimsiz bir MCP ucunu `/mcp`'de
> yayınlar.** Ne bizim filtremiz ne audit'imiz devrededir.

Runtime çözümü yok: sorunu tespit edecek modül, eksik olan modülün kendisi. Bu asimetri
işaretçi-sınıf probe'unun doğasında. Bu yüzden önlem **build tarafında** ve üç katlı:
`test-mcp-opt-in-butunlugu.sh` guard'ı, `gelistirmeler/23-*.md`'de koyu tek cümle, ve
`ZeusMcpProperties.enabled` javadoc'unda tekrar.

## Test

- `McpKopruOlcumuTest` — köprü ölçümü, **kalıcı** (sonraki Spring AI yükseltmesinde tripwire):
  `ToolCallbackProvider` → `syncTools` spec sayısı, tool adları, `@ToolParam` açıklamasının input
  şemasında görünmesi, `McpSyncServer` bean'i, provider yoksa sıfır tool.
- `McpTransportSeciminiKilitleyenTest` — **ölçülmüş davranışı sabitler**: `protocol` yazılmadan
  yayınlanan router function `webMvcSseServerRouterFunction`, `STREAMABLE` ile
  `webMvcStreamableServerRouterFunction`. Spring AI bir gün varsayılanı değiştirirse bu test
  kırmızıya döner ve fail-fast kontrolünün gerekçesi yeniden değerlendirilir.
- `ZeusMcpAutoConfigurationTest` — opt-in **simetri kilidi** (`ZeusSoapAutoConfigurationTest`
  şablonu): `=false` hayatta kalmalı (framework'ün kendi hata mesajı `=false` öneriyor), property
  yoksa aynısı, token'sız `=true` **düşmeli**, `ZeusMcpTools` yoksa `ToolCallbackProvider`
  **olmamalı**, `ai=false, mcp=true` temiz açılmalı.
- `ZeusMcpTokenFilterTest` — doğru token geçer; yanlış ve eksik token aynı `401` +
  `application/problem+json`; `/api/products` dokunulmaz.
- `AuditingToolCallbackTest` — tool tanımı aynen geçer; başarı bir kez loglanır; fırlatan delegate
  audit'lenir **ve yeniden fırlatılır** (yutmama kanıtı).
- `ZeusMcpToolsTest` — `@Tool`'suz nesne reddedilir, çift tool adı reddedilir, `none()` boş.
- `ZeusCapabilitiesSiralamaTest` (`zeus-base`) — `ai-mcp` önce, `ai` sonra.
- Guard'lar: `test-mcp-opt-in-butunlugu.sh` (`app-std`), `test-mcp-uctan-uca.sh` (`app-std+wf`).

`zeus-ai` bugün testsiz; **ağ ucu yayınlayan modül bu borcu devralmaz.**

## Kapsam dışı (bilinçli)

- **MCP client** — roadmap P2 / 2027-Q1. Not: `org.springframework.ai.mcp.` öneki client
  autoconfig'lerini de sahipleneceği için, o faz geldiğinde muhtemelen daha dar bir
  `org.springframework.ai.mcp.client.` satırı `ai-mcp`'den **önce** eklenecek — aynı sıralama
  disiplini bir kat daha derin.
- **Agent harness'ı** (deepagents karşılığı) — ayrı tasarım turu.
- **MCP Resource / Prompt** — v1 yalnız Tool.
- **WebFlux transport** — module'e `spring-webflux` girdiğinde yaşanan `jakarta.websocket` link
  hatasının ikinci turu açılmaz.
- **Gerçek kimlik, tool bazlı yetki, rate limiting** — `zeus-security`.
- **Protokol seviyesi audit** (`tools/list`, `initialize`).

## Riskler

| # | Risk | Tetik | Yedek |
|---|---|---|---|
| R1 | **Property var, jar yok → korumasız MCP ucu** | Pom'unda `zeus-ai-mcp` olmayan uygulamada `zeus.ai.mcp.enabled=true`; ya da `/mcp`'nin 200 dönmesi | `zeus.ai.mcp.enabled=false` (anında veto, framework redeploy'u gerekmez) + ağ seviyesi engel. Tekrarlarsa sahipliği "property evet **ve** işaretçi sınıf yüklenebilir"e genişlet — bu gerçek bir mekanizma değişikliği, spekülatif olarak alınmaz |
| R2 | Servlet API çift kopyası (gömülü Tomcat) | `tomcat-embed-core` kapanışta / module'de / WAR'da görünürse | `spring-boot-starter-web`'e `<exclusions>`. **Düşük:** üç bağımsız katman var ve bu yol bugün de işliyor |
| R3 | Tool istek thread'inde değil | Audit satırında correlation-id boş/farklı, `thread=` reaktif | `CorrelationId`'nin **zaten sağladığı** `capture()`/`restore(Map)` ile MDC'yi filtrede yakala, dekoratörde geri yükle — tek sınıfta ~20 satır. Audit'i dekoratörde tutmak bu yedeği ucuz kılıyor |
| R4 | Spring AI / MCP spec churn | `McpKopruOlcumuTest` kırmızı, ya da bir MCP sınıfı `org.springframework.ai.mcp.` dışına çıkar | `spring.ai.mcp.server.tool-callback-converter=false` + `SyncToolSpecification`'ı kendimiz üret (tek bean takası olacak şekilde tasarlanır). Uygulama yüzeyi `ZeusMcpTools` + `zeus.ai.mcp.*` olduğu için churn N uygulamaya değil tek modüle iner |
| R5 | **Güvenlik boşluğu (kararla ertelenmiş)** | Tool/çağıran bazlı yetki ihtiyacı, veri değiştiren tool, güvenli segment dışına açılma | `zeus-security` gerçek kimliği devralır; dikişler hazır: filtre tek bean (OAuth2 resource-server'a takas), `ZeusMcpAuditor`'a `principal` alanı, `AuditingToolCallback` tool bazlı yetkinin doğal yeri |
| R6 | jakarta module link hatası | Yeşil `verify-module-coverage.sh`'tan sonra deploy'da `NoClassDefFoundError` | Eksik kısa adı `install-zeus-module.sh`'ın export listesine ekle; `test-com-zeus-jakarta-api-kapsama.sh` (türeten, sabitlemeyen) doğrular. **Azaltım = deploy anı ölçümü**; analiz "düşük risk" diyor ama `jakarta.websocket` olayında tam bu analiz yanıldı |
| R7 | `/api/mcp` app'in `/api/**` controller'larıyla çakışır | Mevcut uç 404, ya da `/api/mcp` gölgelenir | Tek property ile uç taşınır (filtre yolu aynı property'den türettiği için tek yerde değişir) |
| R9 | **MCP hata gövdesi iç detay sızdırıyor** (ölçüldü). Protokol hatasında Spring AI'ın transport'u istisnayı JSON'a serileştiriyor ve gövdeye `stackTrace` (sınıf adları, dosya/satır, `classLoaderName: "com.zeus"`) koyuyor — ör. eksik session id'de. Bizim ürettiğimiz 401 temiz; sızdıran taraf SDK/transport | `/api/mcp`'ye geçerli token'la bozuk bir JSON-RPC isteği; yanıtta `stackTrace` alanı | v1'de kabul: uç paylaşılan sırla korunuyor, yani gövdeyi yalnız sırrı bilen görür. Kalıcı çözüm, tool gövdesi hatalarında olduğu gibi protokol hatalarını da sarmak için transport seviyesinde bir hata eşlemesi gerektirir → `zeus-security` ile birlikte ele alınacak. Dokümana "bilinen boşluk" olarak yazıldı |
| R8 | **Sessiz SSE → korunmayan uç** (ölçülmüş davranış). `protocol` yazılmazsa Spring AI deprecated SSE transport'unu `sse-endpoint`'te yayınlar; token filtresi streamable yolunu koruduğu için ona dokunmaz | `protocol` satırı silinirse / `SSE` yazılırsa; ya da `/sse`'nin 200 dönmesi | Ctor fail-fast açılışı düşürür (yalnız `STREAMABLE` kabul). `McpTransportSeciminiKilitleyenTest` varsayılanın değişmesini yakalar. Uçtan uca guard'a `/sse`'nin **404 döndüğü** iddiası eklenir |

## Değerlendirilen alternatifler

**MCP'yi `zeus-ai` içine koymak.** `sunum/mcp-server-stratejisi.pptx` slayt 4 bunu öneriyor:
*"Uygulama ekibinin işi üç adıma iner: 1. `zeus-ai` bağımlılığını ekler 2. Servis metodunu
`@McpTool` ile işaretler 3. Deploy eder."* Teknik olarak da ucuz: MCP autoconfig'lerinin tamamı
`org.springframework.ai.` altında, yani mevcut `ai` yeteneği onları zaten sahipleniyor ve
`ZeusCapabilities`'e hiç dokunulmazdı. **Reddedildi:** AI kullanan her uygulama MCP server
jar'larını taşır ve ucu kapatmak için ikinci bir property (`spring.ai.mcp.server.enabled=false`)
öğrenmek gerekirdi — yeteneğin "uygulamanın öğrenmesi gereken tek kavram olsun" ilkesini tam
tersine çevirir. Ağ ucu yayınlamak açık opt-in olmalı. **Sunumun bu cümlesi düzeltilecek.**

**Zeus'un kendi `@ZeusMcpTool` anotasyonu.** Roadmap'in risk azaltım maddesi birebir bunu
söylüyor: *"uygulama kodu Spring AI API'sini doğrudan değil zeus-ai-mcp üzerinden kullanır."*
**Reddedildi:** aynı metodu hem LLM tool'u hem MCP tool'u yapmak için iki anotasyon dünyası ve iki
şema üretim yolu doğardı. `@Tool` zaten CLAUDE.md'de tek meşru Spring AI import istisnası; onu
yeniden kullanmak hem izolasyonu korur (köprü framework'te) hem tool'u bir kez yazdırır.

**Doğrudan Spring AI `@McpTool`.** En az framework kodu; anotasyon tarayıcısı
(`McpServerAnnotationScannerAutoConfiguration`) işi yapardı. **Reddedildi:** uygulama kodunda
ikinci bir Spring AI import istisnası açar ve MCP API'si değiştiğinde N uygulama birden etkilenir —
roadmap'in önlediğini söylediği riskin ta kendisi.

**Şimdi `zeus-security` ile başlamak.** Sunumun 4 kabul koşulunu gerçekten karşılardı.
**Reddedildi:** kapsam ciddi büyür (yeni yetenek modülü + yeni jar seti + module yenileme/restart +
jakarta API riski) ve MCP teslimi ona bağımlı hâle gelir. v1 fail-closed + salt okunur yüzey +
audit ile teslim edilebilir; boşluk yazılı.
