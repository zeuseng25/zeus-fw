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
| `spring-boot-starter-parent` | `zeus-parent` | Uygulamaların parent'ı — plugin/derleme yönetimi |
| `spring-boot-*` | `zeus-base/-logger/-database/-service/-redis/-batch` | Yetenek modülleri |

## Mimari — Maven Multi-Module + Parent Zinciri

```
spring-boot-starter-parent:3.1.3
        ▲ parent
   zeus-fw  (kök: packaging=pom, <revision>, aggregator <modules>, flatten-maven-plugin)
        ▲ parent                    ▲ parent
 zeus-dependencies (BOM)       zeus-parent (pluginManagement + zeus BOM import)
        │ import                     ▲ parent
        └──────────► ┌──────┬──────┼──────┬──────┬──────┐
                zeus-base -logger -database -service -redis -batch  (jar)
```

- **groupId:** `com.zeus` · **base paket:** `com.zeus.framework.<modul>` · **Java 17** · **Spring Boot 3.1.3 / Spring 6.0.11**.
- Dış uygulamalar `<parent>` = `zeus-parent` alır; modülleri **sürüm yazmadan** bağımlılık ekler (BOM yönetir).

### Modüller

| Modül | artifactId | Durum | İçerik |
|-------|-----------|-------|--------|
| base | `zeus-base` | ✅ gerçek | `GlobalExceptionHandler` + `ResourceNotFoundException` (ProblemDetail) |
| logger | `zeus-logger` | ✅ gerçek | `RequestLoggingFilter` (her istek loglanır) |
| database | `zeus-database` | ✅ gerçek | `StoredProcedureExecutor` (JDBC, önbellekli) + `JpaStoredProcedureExecutor` |
| service | `zeus-service` | ✅ gerçek | `AbstractCrudService` + `DtoMapper` |
| redis | `zeus-redis` | 🚧 iskelet | RedisTemplate/cache (planlanan) |
| batch | `zeus-batch` | 🚧 iskelet | Spring Batch job/step (planlanan) |
| war-defaults | `zeus-war-defaults` | ✅ gerçek | WAR uygulamalarına build'de enjekte edilen `jboss-deployment-structure.xml` **şablonu** (slot yer tutuculu). Uygulamalar bu dosyayı elle yazmaz; zeus-parent'ın `zeus-generated-descriptor` profili üretir. Bkz. `gelistirmeler/10-versiyonlu-slot-uretilen-descriptor.md`. |
| wildfly-module | `zeus-wildfly-module` | ⚙️ pom | Jar üretmez: WildFly paylaşımlı `com.zeus` module'üne girecek 3. parti runtime bağımlılıklarının **birleşimi**. Bkz. `gelistirmeler/08-wildfly-module-dagitim.md`. |

## Tek Versiyon Yönetimi (`${revision}`) — ÖNEMLİ

Sürüm yalnızca **kök `pom.xml`'deki tek property**'de tanımlıdır: `<revision>1.0.0-SNAPSHOT</revision>`. Tüm modüller `<version>${revision}</version>` ve parent referansını `${revision}` ile kullanır. Yayınlanan pom'larda `${revision}` somut sürüme dönüşsün diye kökte **flatten-maven-plugin** (`resolveCiFriendliesOnly`) çalışır. Sürümü değiştirmek için **yalnızca o tek satırı** güncelle. Spring Boot sürümü kökün parent'ından, Spring Framework sürümü `spring-framework.version` property'sinden gelir. Detay: `gelistirmeler/07-bom-parent-surum-yonetimi.md`.

## Auto-Configuration Deseni (her modül)

Her modül, bağımlılık eklenince kendiliğinden devreye girer (Spring Boot starter mantığı):
1. `com.zeus.framework.<modul>.Zeus<Modul>AutoConfiguration` — `@AutoConfiguration` ile işaretli; bean'leri `@ConditionalOn...` ile koşullu kaydeder.
2. `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` — auto-config sınıfının tam adını içerir.

## Yeni Modül / Yeni Kod Ekleme Kuralı

**Yeni modül eklerken:**
1. `zeus-<ad>/pom.xml` (parent = `zeus-parent`, `<version>` yazma — parent'tan gelir).
2. Kökteki `pom.xml` `<modules>`'a ekle.
3. `zeus-dependencies` BOM'una `<dependency>` (sürüm `${revision}`) ekle.
4. `Zeus<Ad>AutoConfiguration` + `.imports` dosyasını oluştur.
5. `gelistirmeler/NN-zeus-<ad>.md` dokümanını yaz.

**Mevcut modüle kod eklerken:** önce `@AutoConfiguration`'da koşullu bean kaydını ekle, sonra ilgili modül dokümanını güncelle.

## Build

```bash
./mvnw clean install      # 8 artefaktı ~/.m2/repository/com/zeus/ altına kurar
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
