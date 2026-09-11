# CLAUDE.md — zeus-fw (Zeus Framework)

Bu dosya Zeus Framework'ün mimarisini ve çalışma kurallarını tanımlar. Claude Code bu projede çalışırken bu kurallara uyar.

## Ekosistem / Proje İlişkisi (ÖNEMLİ — hangi dizin açılırsa açılsın)

- **`zeus-fw` = bu proje, framework.** Onu **N uygulama** parent olarak kullanır (ör. `../spring-wildfly-arch`).
- **Burada yapılan değişiklik tüm tüketen uygulamaları etkiler** — geriye dönük uyumluluk ve lockstep
  (paylaşımlı `com.zeus` module / BOM) bu yüzden kritiktir.
- Kural: **framework-içi** (BOM, parent, paylaşımlı module, sürüm/CVE yönetimi) işler ve dokümanları
  **burada** (`gelistirmeler/`) yaşar; **bir uygulamaya özel** işler ilgili uygulamanın reposunda. Uygulamaların
  bu framework'le ne yapması gerektiğini anlatan platform süreçleri de burada dokümante edilir.

## Proje Özeti

**Spring Boot mimarisine birebir benzeyen** kurumsal bir Java framework'ü. `spring-wildfly-arch` gibi uygulamalar bu framework'ü **parent** olarak alır (`zeus-parent`), modüllerini bağımlılık olarak kullanır (datasource'a bağlanır, log atar, hata yönetir). Spring Boot/Spring sürümleri ve tüm modül sürümleri **tek noktadan** yönetilir.

Spring Boot karşılıkları:

| Spring Boot | Zeus | Görev |
|-------------|------|-------|
| `spring-boot-dependencies` | `zeus-dependencies` | BOM — tüm sürümler |
| `spring-boot-starter-parent` | `zeus-parent` | Standart (REST) uygulamaların parent'ı — plugin/derleme yönetimi |
| — | `zeus-soap-parent` / `zeus-bff-parent` / `zeus-standalone-parent` | TİP parent'ları: SOAP (ince WAR + com.zeus.soap module — CXF artık paylaşımlı `com.zeus`'ta, `com.zeus.soap` bugün **boş** (0 jar); ileride CXF'in `com.zeus`'tan AYRIŞMASI ihtimali için korunan mimari dikiş. `webservices` subsystem dışlaması bundan BAĞIMSIZ bir descriptor direktifidir — module import'u silinse de yürürlükte kalır) / BFF (gateway, FAT WAR, izole) / **Standalone** (self-contained WAR, com.zeus'a bağlanmaz — classloader izolasyonu gereken servisler). Bkz. `gelistirmeler/14-uygulama-tipi-parentlar.md` |
| `spring-boot-*` | `zeus-base/-logger/-database/-service/-ai/-redis/-batch/-soap/-sms/-bff-starter/-bff-login` | Yetenek modülleri |

## Mimari — Maven Multi-Module + Parent Zinciri

```
spring-boot-starter-parent:4.0.7
        ▲ parent
   zeus-fw  (kök: packaging=pom, <revision>, aggregator <modules>, flatten-maven-plugin)
        ▲ parent                    ▲ parent
 zeus-dependencies (BOM)       zeus-parent (pluginManagement + zeus BOM import)
        │ import                     ▲ parent
        └──────────► ┌──────┬──────┼──────┬──────┬──────┐
                zeus-base -logger -database -service -redis -batch  (jar)
```

- **groupId:** `com.zeus` · **base paket:** `com.zeus.framework.<modul>` · **Java 25** · **Spring Boot 4.0.7 / Spring 7.0.x**.
- Dış uygulamalar `<parent>` = `zeus-parent` alır; modülleri **sürüm yazmadan** bağımlılık ekler (BOM yönetir).

### Modüller

| Modül | artifactId | Durum | İçerik |
|-------|-----------|-------|--------|
| base | `zeus-base` | ✅ gerçek | `GlobalExceptionHandler` + `ResourceNotFoundException` (ProblemDetail); `CorrelationId` + `ZeusServletInitializer` (WAR uygulamalarının ana sınıfı bunu genişletir) |
| logger | `zeus-logger` | ✅ gerçek | `RequestLoggingFilter` (her istek loglanır) + `CorrelationIdFilter` (uçtan uca izleme; bkz. `gelistirmeler/18-correlation-id.md`) |
| database | `zeus-database` | ✅ gerçek | `StoredProcedureExecutor` (JDBC, önbellekli) + `JpaStoredProcedureExecutor` |
| service | `zeus-service` | ✅ gerçek | `AbstractCrudService` + `DtoMapper` |
| ai | `zeus-ai` | ✅ gerçek | `ZeusAiAssistant` (sohbet + yapılandırılmış çıktı + tool calling) — Spring AI 2.x, OpenAI-uyumlu endpoint (vLLM/LiteLLM/OpenRouter). Bkz. `gelistirmeler/15-zeus-ai.md` |
| redis | `zeus-redis` | 🚧 iskelet | RedisTemplate/cache (planlanan) |
| batch | `zeus-batch` | 🚧 iskelet | Spring Batch job/step (planlanan) |
| soap | `zeus-soap` | ✅ gerçek | `ZeusSoapEndpointRegistrar` — `@WebService` bean'lerini `/services/*` altında yayınlar (Apache CXF / JAX-WS). SOAP tipi uygulamalar için. Bkz. `gelistirmeler/14-uygulama-tipi-parentlar.md` |
| sms | `zeus-sms` | ✅ gerçek | `ZeusSmsClient` — CXF SOAP **istemcisi**. CXF artık paylaşımlı `com.zeus` module'ünde; kullanan uygulama **hiçbir opt-in property yazmaz** (eski iki-property mekanizması kaldırıldı). Bkz. `gelistirmeler/20-zeus-sms.md` |
| bff-starter | `zeus-bff-starter` | ✅ gerçek | `ZeusBffFilter` + `ZeusBffProperties` — Spring Cloud Gateway Server MVC routing + React paketi sunumu (BFF tipi) |
| bff-login | `zeus-bff-login` | ✅ gerçek | `LoginFilterHook` + `SessionHook` — BFF oturum/login akışı |
| war-defaults | `zeus-war-defaults` | ✅ gerçek | WAR uygulamalarına build'de enjekte edilen `jboss-deployment-structure.xml` **şablonu** (slot yer tutuculu, tip başına ayrı dizin). Uygulamalar bu dosyayı elle yazmaz; zeus-parent'ın `zeus-generated-descriptor` profili üretir. Bkz. `gelistirmeler/10-versiyonlu-slot-uretilen-descriptor.md`. |
| wildfly-module | `zeus-wildfly-module` | ⚙️ pom | Jar üretmez: paylaşımlı `com.zeus` module'üne girecek 3. parti runtime bağımlılıklarının **birleşimi** — CXF (`cxf-spring-boot-starter-jaxws`) dahil (2026-09-11'den beri). İki kaynaktan beslenir: zeus-* modülleri (kapanışları otomatik gelir) + uygulamaların doğrudan kullandığı yığın (elle). Bkz. `gelistirmeler/08-wildfly-module-dagitim.md`. |
| soap-wildfly-module | `zeus-soap-wildfly-module` | ⚙️ pom | CXF sözleşmesi; `com.zeus` ile küme farkı alınarak `com.zeus.soap` module'üne dönüştürülür. Fark bugün **boş kümedir** (CXF zaten `com.zeus`'ta) — üretilen `com.zeus.soap` 0 jar içerir. |

Ayrıca **tip parent'ları** (jar üretmez): `zeus-soap-parent`, `zeus-bff-parent` — WAR paketleme
davranışını seçerler. Jar modülleri bunlara değil `zeus-parent`'a bağlıdır
(gerekçe: `gelistirmeler/14-uygulama-tipi-parentlar.md`).

## Tek Versiyon Yönetimi (`${revision}`) — ÖNEMLİ

Sürüm yalnızca **kök `pom.xml`'deki tek property**'de tanımlıdır: `<revision>2.0.0-SNAPSHOT</revision>`. Tüm modüller `<version>${revision}</version>` ve parent referansını `${revision}` ile kullanır. Yayınlanan pom'larda `${revision}` somut sürüme dönüşsün diye kökte **flatten-maven-plugin** (`resolveCiFriendliesOnly`) çalışır. Sürümü değiştirmek için **yalnızca o tek satırı** güncelle. Spring Boot sürümü kökün parent'ından, Spring Framework sürümü `spring-framework.version` property'sinden gelir. Detay: `gelistirmeler/07-bom-parent-surum-yonetimi.md`.

## Auto-Configuration Deseni (her modül)

Her modül, bağımlılık eklenince kendiliğinden devreye girer (Spring Boot starter mantığı):
1. `com.zeus.framework.<modul>.Zeus<Modul>AutoConfiguration` — `@AutoConfiguration` ile işaretli; bean'leri `@ConditionalOn...` ile koşullu kaydeder.
2. `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` — auto-config sınıfının tam adını içerir.

## Yetenek Opt-in'i (paylaşımlı module'ün autoconfig yan etkisi)

`com.zeus` module'ü tüm uygulamaların bağımlılık **birleşimi**dir ve daraltılmaz — AI'lı ve
AI'sız uygulama aynı module'ü paylaşır. Bir yeteneğin (bugün: `ai`, `database`, `soap`) 3. parti
autoconfig'leri, tüketen uygulama o yeteneği `zeus.<yetenek>.enabled=true` ile **açıkça
istemedikçe** hiç devreye girmez — bunu `zeus-base`'deki `ZeusCapabilities` (kayıt) +
`ZeusAutoConfigurationFilter` (filtre) + `ZeusCapabilityVerifier` (bağımlılık var ama property
yok çelişkisini yakalayan denetleyici) sağlar. Çalışma zamanı **fail-open**dır (tanımadığı
sınıfı vetolamaz), build **fail-closed**dır (`scripts/test-autoconfig-sahipligi.sh`,
module'deki her autoconfig sınıfının bir yeteneğe ya da her-zaman-serbest listesine düştüğünü
zorunlu kılar). Tam tasarım, API, kaçış kapıları ve yetenek tablosu: `gelistirmeler/21-yetenek-opt-in.md`.

**Yeni bir modül paylaşımlı `com.zeus` module'üne yeni bir 3. parti autoconfig paketi
getiriyorsa** (aşağıdaki "Yeni Modül Ekleme Kuralı"nın bir parçası olarak), o paket
`ZeusCapabilities.HEPSI`'ye YENİ bir yetenek olarak kaydedilmelidir — aksi hâlde o modülün
autoconfig'leri hiçbir opt-in olmadan **her** uygulamada çalışmaya başlar ve
`test-autoconfig-sahipligi.sh` build'i kırar.

## Yeni Modül / Yeni Kod Ekleme Kuralı

**Yeni modül eklerken:**
1. `zeus-<ad>/pom.xml` (parent = `zeus-parent`, `<version>` yazma — parent'tan gelir).
2. Kökteki `pom.xml` `<modules>`'a ekle.
3. `zeus-dependencies` BOM'una `<dependency>` (sürüm `${revision}`) ekle.
4. `Zeus<Ad>AutoConfiguration` + `.imports` dosyasını oluştur.
5. Modül paylaşımlı `com.zeus` module'üne yeni bir 3. parti autoconfig paketi getiriyorsa,
   `ZeusCapabilities.HEPSI`'ye bir `ZeusCapability` satırı ekle (bkz. yukarıdaki "Yetenek
   Opt-in'i").
6. `gelistirmeler/NN-zeus-<ad>.md` dokümanını yaz.

**Mevcut modüle kod eklerken:** önce `@AutoConfiguration`'da koşullu bean kaydını ekle, sonra ilgili modül dokümanını güncelle.

## Build

```bash
mvn clean install      # 8 artefaktı ~/.m2/repository/com/zeus/ altına kurar
```

Bağımlılık/kod değişince framework'ü yeniden kur; tüketen uygulamalar (`spring-wildfly-arch`) yeni jar'ları ~/.m2'den alır. WildFly tarafında 3. parti `com.zeus` module'ü ancak **3. parti** bağımlılık değişince yenilenir (zeus jar'ları WAR içinde taşınır).

### Paylaşımlı WildFly module (PLATFORM sorumluluğu)

`com.zeus` module'ü tek ve paylaşımlıdır; onu **bu repo** üretir (uygulama değil):

```bash
./scripts/install-zeus-module.sh                                  # varsayılan WILDFLY_HOME, slot: main
./scripts/install-zeus-module.sh --slot 1.1.0                     # versiyonlu slot (immutable; kurulum restart'sız)
WILDFLY_HOME=/path/staging-wildfly ./scripts/install-zeus-module.sh   # staging hedefi
./scripts/slot-inventory.sh                                       # hangi slot kurulu / hangi app hangi slot'ta + politika denetimi
SLOT=1.1.0 WILDFLY_HOME=/path/staging ./scripts/verify-staging.sh <app>  # restart'sız slot doğrulama gate'i
./scripts/generate-war-excludes.sh --check    # WAR dışlama listeleri module ile uyumlu mu
# install-zeus-module.sh bunu kendi sonunda --write ile zaten çağırır.
# main slot'u güncellenince WildFly RESTART şart; YENİ slot eklemek restart gerektirmez.
# Uygulamalar slot'a, build'de üretilen descriptor'daki zeus.module.slot ile bağlanır
# (bkz. gelistirmeler/10-versiyonlu-slot-uretilen-descriptor.md).
```

İçerik `zeus-wildfly-module` modülünün runtime bağımlılık kapanışından gelir. CVE yaması =
`zeus-dependencies` BOM'da override → bu script ile module'ü yenile. Detay:
`gelistirmeler/08-wildfly-module-dagitim.md` (+ CVE süreci: `gelistirmeler/09-cve-guvenlik-yamalama.md`).

## Konvansiyonlar

- Kod yorumları **Türkçe**, çevredeki stille tutarlı.
- Base paket `com.zeus.framework.<modul>`. Lombok kullanılabilir (parent annotation processor yolu hazır).
- Modüller arası bağımlılık: hepsi `zeus-base`'e bağlanır; `zeus-base` SLF4J'yi transitive yayar.
- Auto-config bean'leri `@ConditionalOnMissingBean` ile — uygulama kendi bean'ini verirse override edebilsin.

## Dokümantasyon Kuralı

Her bileşen/geliştirme `gelistirmeler/` altında **numaralı** md dosyasına yazılır: `NN-bilesen-adi.md`. Genel mimari `00-Genel-Mimari.md`. Yeni bileşende sıradaki numarayla yeni dosya açılır.

## Kısıtlar

- `.md` dosyaları yalnızca bu proje dizinine yazılır.
- Bu framework'ü tüketen proje: `../spring-wildfly-arch` (örnek/kanıt uygulaması).
