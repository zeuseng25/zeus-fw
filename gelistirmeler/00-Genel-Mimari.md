# 00 — Zeus Framework: Genel Mimari

## Amaç

`spring-wildfly-arch` gibi projelerin tekrar eden altyapısını (exception/ProblemDetail, datasource, stored-procedure helper, loglama, sürüm yönetimi) **tek bir kurumsal framework**'te toplamak. Zeus Framework, **Spring Boot mimarisine birebir benzer** kurgulanır:

| Spring Boot | Zeus karşılığı | Görev |
|-------------|----------------|-------|
| `spring-boot-dependencies` | **`zeus-dependencies`** | BOM — tüm sürümleri yönetir |
| `spring-boot-starter-parent` | **`zeus-parent`** | Uygulamaların parent'ı — plugin/derleme yönetimi |
| `spring-boot-*` (web, jdbc, ...) | **`zeus-base/-logger/-database/-service/-redis/-batch`** | Yetenek modülleri |

Bir uygulama `zeus-parent`'ı **parent** olarak alır ve ihtiyaç duyduğu modülleri **sürüm yazmadan** bağımlılık olarak ekler; modüller `@AutoConfiguration` ile kendiliğinden devreye girer.

## Modüller

| Modül | groupId:artifactId | Sorumluluk |
|-------|--------------------|------------|
| base | `com.zeus:zeus-base` | Ortak exception/ProblemDetail, çekirdek tipler (temel) |
| logger | `com.zeus:zeus-logger` | Loglama yapılandırması, istek loglama, WildFly logging uyumu |
| database | `com.zeus:zeus-database` | JNDI/direct datasource, stored-procedure helper'ları |
| service | `com.zeus:zeus-service` | Base service/transaction/DTO konvansiyonları |
| redis | `com.zeus:zeus-redis` | RedisTemplate/cache soyutlaması (iskelet) |
| batch | `com.zeus:zeus-batch` | Spring Batch job/step altyapısı (iskelet) |

> Durum: **base, logger, database, service** gerçek kod içerir (hata yönetimi, istek loglama, stored-procedure helper'ları, base CRUD service). **redis, batch** henüz iskelet (`@AutoConfiguration` kancası + `.imports`); gerçek kod her modülün kendi dokümanındaki "Planlanan İçerik"e göre eklenecek.

## Parent Zinciri

```
spring-boot-starter-parent:4.0.7        (Spring/Hibernate/Jackson/logging sürümleri + plugin yönetimi)
        ▲ parent
   zeus-fw  (kök: packaging=pom, <revision>, aggregator <modules>, flatten-maven-plugin)
        ▲ parent                    ▲ parent
 zeus-dependencies (BOM)       zeus-parent (pluginManagement + zeus BOM import)
        │ import scope               ▲ parent
        └───────────────► ┌──────┬──────┼──────┬──────┬──────┐
                     zeus-base -logger -database -service -redis -batch -soap -bff-*  (jar)
                                     ▲ parent (tip parent'ları da zeus-parent'tan türer)
                     zeus-soap-parent (SOAP/CXF, ince WAR + com.zeus.soap)
                     zeus-bff-parent  (BFF/gateway, FAT WAR, izole)
```

> **Uygulama tipine özel parent'lar:** REST → `zeus-parent`, SOAP → `zeus-soap-parent`,
> BFF → `zeus-bff-parent`. Paketleme/descriptor davranışını parent belirler; hepsi tek
> `${revision}` ile sürümlenir. Detay: `14-uygulama-tipi-parentlar.md`.

- **groupId:** hepsi `com.zeus` · **base paket:** `com.zeus.framework.<modul>`
- **Java 25 · Spring Boot 4.0.7 · Spring Framework 7.0.x** (Framework sürümünü Boot BOM yönetir; pin YOK — bkz. 13-java25-boot4-wildfly41-yukseltme.md).
- `zeus-dependencies`, dışarıdan tek başına `scope=import` ile alınabilsin diye kendi içinde `spring-boot-dependencies`'i de import eder (kendi kendine yeten BOM).

## Tek Versiyon Yönetimi (`${revision}`)

Tüm zeus modülleri **tek** sürümle yönetilir. Sürüm yalnızca kök `zeus-fw/pom.xml` içindeki tek property'de tanımlıdır:

```xml
<properties>
    <revision>2.0.0-SNAPSHOT</revision>
</properties>
```

Tüm modüller `<version>${revision}</version>` kullanır. Yayınlanan (install/deploy) pom'larda `${revision}` ifadesinin somut sürüme dönüşmesi için kökte **flatten-maven-plugin** (`resolveCiFriendliesOnly` modu) çalışır — bu mod pom'un geri kalanını (dependencyManagement/pluginManagement) aynen korur. Sürümü değiştirmek için **yalnızca bu tek satırı** güncellemek yeterlidir.

Spring Boot / Spring sürümleri de buradan yönetilir: Spring Boot sürümü kökün parent'ından (`spring-boot-starter-parent:4.0.7`) gelir; Spring Framework sürümü Boot BOM'unca yönetilir (ayrıca pin'lenmez).

## Bir Uygulama Framework'ü Nasıl Kullanır?

```xml
<!-- 1) Parent olarak zeus-parent -->
<parent>
    <groupId>com.zeus</groupId>
    <artifactId>zeus-parent</artifactId>
    <version>2.0.0-SNAPSHOT</version>
    <relativePath/>
</parent>

<!-- 2) İhtiyaç duyulan modüller — SÜRÜM YAZMADAN (BOM yönetir) -->
<dependencies>
    <dependency>
        <groupId>com.zeus</groupId>
        <artifactId>zeus-database</artifactId>
    </dependency>
    <dependency>
        <groupId>com.zeus</groupId>
        <artifactId>zeus-base</artifactId>
    </dependency>
</dependencies>
```

Modüllerdeki `@AutoConfiguration` sınıfları `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` üzerinden otomatik yüklenir — tıpkı Spring Boot starter'ları gibi, ekstra konfigürasyon gerekmez.

> Bu adımda `spring-wildfly-arch` **değiştirilmedi**. Onu `zeus-parent`'a taşıma ve ortak kodu modüllere aktarma işi ayrı bir adımda (ayrı onayla) yapılacaktır.

## İnce WAR / WildFly `com.zeus` Module ile İlişki (Paketleme Politikası)

`spring-wildfly-arch` ince WAR stratejisiyle çalışır ama bağımlılıklar **iki gruba** ayrılır:

| Grup | Nereye | Neden |
|------|--------|-------|
| **zeus-* (framework) jar'ları** | **WAR içine** (`WEB-INF/lib`) | Framework kodu uygulamayla birlikte sık değişir; WAR'a koymak her değişiklikte module yeniden kurmayı gerektirmez. |
| **3. parti jar'lar** (Spring, Hibernate, Jackson, ...) | **WildFly `com.zeus` module** | Ağır ve durağan; sunucuda bir kez durur, WAR ~KB seviyesinde kalır. |

Bu politika `zeus-parent`'ta standartlaştırılmıştır — `maven-war-plugin`:

```xml
<packagingExcludes>${zeus.war.packaging-excludes}</packagingExcludes>
```

**Listenin polaritesi DENYLIST'tir.** Property'nin değeri elle yazılmaz; paylaşımlı WildFly
module'ünün **bağımlılık sözleşmesinden ÜRETİLİR** (`scripts/generate-war-excludes.sh --write`,
`zeus-parent/pom.xml`'deki `ZEUS-WAR-EXCLUDES` marker bloğuna). Kural:

> **module'ün verdiği jar'ı WAR'dan at; kalan her şeyi WAR'da taşı.**

Bir dönem tersi geçerliydi — `%regex[WEB-INF/lib/(?!zeus-).*\.jar]`, yani "`zeus-` ile
başlamayan her şey atılır" (allowlist). O kural **kaldırıldı**: module'de OLMAYAN bir
bağımlılığı da sessizce siliyor ve WildFly'da `NoClassDefFoundError` üretiyordu. Bugün
app'e özel bir kütüphane hiçbir şey yazılmadan WAR'da taşınır. Gerekçe:
`19-war-paketleme-module-farkindaligi.md`.

Bugünkü ölçüm (2026-09-09): standart liste **168 alternatif** (159 artifactId + 9 sabit-kuyruk
kalıbı), `com.zeus` module'ünde **157 jar**. `spring-wildfly-arch` WAR'ı **88 KB** — içinde
yalnız 6 `zeus-*` jar'ı + uygulama sınıfları.

Tamamlayıcı olarak `zeus-fw/scripts/install-zeus-module.sh` (module üretimi PLATFORM işidir, uygulamada bu araç yoktur) module'e jar toplarken **zeus-* jar'larını dışlar** (aksi halde sınıflar hem module'de hem WAR'da olur → LinkageError riski). Böylece zeus-* yalnızca WAR'da, 3. parti yalnızca module'de bulunur. Module değiştiğinde WildFly restart gerekir; sadece zeus kodu değiştiğinde restart gerekmez (WAR yeniden deploy yeter).

> Detaylı entegrasyon: `spring-wildfly-arch/gelistirmeler/13-zeus-framework-entegrasyonu.md`.

## Build & Doğrulama

```bash
cd zeus-fw
mvn clean install      # 8 artefaktı ~/.m2/repository/com/zeus/ altına kurar
```

Doğrulama:
- `~/.m2/repository/com/zeus/` altında `zeus-fw`, `zeus-dependencies`, `zeus-parent` (pom) + `zeus-base/-logger/-database/-service/-redis/-batch` (jar) `2.0.0-SNAPSHOT` ile oluşur.
- Yayınlanan pom'larda `${revision}` yerine `2.0.0-SNAPSHOT` yazar (flatten doğrulaması).
- Her modül jar'ında `META-INF/spring/...AutoConfiguration.imports` bulunur.

## İlgili

- `01-zeus-base.md` · `02-zeus-logger.md` · `03-zeus-database.md` · `04-zeus-service.md` · `05-zeus-redis.md` · `06-zeus-batch.md`
- `spring-wildfly-arch/gelistirmeler/08-com-zeus-module.md` (ince WAR / WildFly module)
