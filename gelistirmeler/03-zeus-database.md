# 03 — zeus-database

## Amaç

Veri erişim altyapısını standartlaştırmak. `spring-wildfly-arch`'ın en yüksek tekrar kullanım değerine sahip kısmı: profil tabanlı datasource (WildFly JNDI vs lokal direct JDBC) ve **stored-procedure** çağrı yardımcıları (`JpaRepository` kullanılmaz).

## Mevcut Durum

**Stored-procedure helper'ları** (`com.zeus.framework.database.sp`):
- `StoredProcedureExecutor` (interface) — JDBC tabanlı SP çağrı sözleşmesi:
  - `query(catalog, procedure, cursorName, rowMapper, inParams)` → REF CURSOR sonucunu `RowMapper` ile maps eder.
  - `execute(catalog, procedure, inParams)` → OUT parametrelerini map olarak döndürür.
  - IN parametresiz çağrılar için kısayol (default) metotlar.
- `JdbcStoredProcedureExecutor` — `SimpleJdbcCall` implementasyonu; compile edilmiş çağrıları katalog+procedure(+cursor) anahtarıyla **önbelleğe alır** (her çağrıda metadata sorgusu yok).
- `JpaStoredProcedureExecutor` — `EntityManager.createStoredProcedureQuery` ile pozisyonel parametre: `query(proc, resultClass, in...)` (REF CURSOR → entity) ve `executeWithOut(proc, outType, in...)` (skaler OUT).

Auto-config'ler (ortam UYGULAMA AYARI olmadan otomatik seçilir):
- **`ZeusJndiDataSourceAutoConfiguration`** (WildFly/JNDI) — `OnZeusJndiCondition` (birincil JNDI lookup denenir) eşleşince aktif. `@Primary` DataSource (JNDI Oracle) + `StoredProcedureExecutors` kaydeder. `before = DataSourceAutoConfiguration` (Spring Boot boş datasource kurmaya çalışmasın).
- **`ZeusDatabaseAutoConfiguration`** (lokal + JPA) — JNDI yokken Spring Boot'un ambient DataSource'u için `StoredProcedureExecutors` kurar (her iki getter tek DB'ye gider); JPA varsa `JpaStoredProcedureExecutor`. `after = DataSourceAutoConfiguration`.

`ZeusDatabaseProperties` (`zeus.database.*`): standart datasource'ların JNDI adları **framework varsayılanı** (`oracle-jndi`, `report-jndi`); app set etmez.
`StoredProcedureExecutors`: isimli getter'lar `getOracleDs()` / `getReportDs()` (lazy).
`ZeusDataSources.jndi(name)`: JNDI lookup yardımcısı (framework-içi).

Bağımlılıklar: `spring-boot-autoconfigure`, `spring-jdbc`, `spring-orm`, `jakarta.persistence-api` (provided), `zeus-base`.

## Datasource'lar (tamamen framework-yönetimli)

**İlke:** Datasource ile ilgili hiçbir şey (kod, `@Bean`, property, JNDI adı) **uygulamada bulunmaz**.
Standart datasource'lar (OracleDS, ReportingDS) zeus-database'in kavramıdır. Uygulama yalnız
`StoredProcedureExecutors` inject edip **isimli getter** ile seçer:

```java
private final StoredProcedureExecutors sp;     // tek inject; framework kurar

sp.getOracleDs().query(PKG, "GET_ALL", CUR, MAPPER);   // OracleDS
sp.getReportDs().execute(PKG, "BUILD_REPORT", args);   // ReportingDS
```

**App tarafı:** hiçbir datasource ayarı yok. WildFly'a deploy → `OnZeusJndiCondition` JNDI'yı algılar →
JNDI modu. Lokal (embedded) → JNDI yok → ambient DataSource; her iki getter o tek DB'ye gider (dev).

**JNDI adları:** framework varsayılanı (`ZeusDatabaseProperties.DEFAULT_ORACLE_JNDI` / `DEFAULT_REPORT_JNDI`).
Bir kurulumda farklıysa yalnız o ortamda override edilir: `zeus.database.report-jndi=...` (tipik app hiçbir şey yazmaz).

**Neden isimli getter (`getOracleDs`), `get("oracle")` değil:** OracleDS/ReportingDS kurumsal
**standart** datasource'lardır (framework kavramı) → derleme-zamanı, tip-güvenli erişim. Yeni bir
standart datasource, framework'e yeni bir getter olarak eklenir.

**Lazy:** getter'lar ilk çağrıda üretilir → ReportingDS'i hiç kullanmayan uygulama, o datasource
ortamda olmasa bile başlangıçta hata almaz. JPA (`JpaStoredProcedureExecutor`) tek persistence unit →
`@Primary` (Oracle) datasource. Çalışan örnek: `spring-wildfly-arch/gelistirmeler/03-wildfly-datasource.md`.

> `spring-wildfly-arch`'ın `JdbcProductRepository` ve `JpaProductRepository`'si artık bu helper'ları kullanır (bkz. `13-zeus-framework-entegrasyonu.md`). Datasource (JNDI/direct) yapılandırması uygulama tarafında Spring Boot autoconfig + profillerle yapılır.

## Planlanan İçerik

- Toplu/çok cursor'lu çağrılar ve tip-güvenli parametre kurucu (builder).

## Kullanım

```xml
<dependency>
    <groupId>com.zeus</groupId>
    <artifactId>zeus-database</artifactId>
</dependency>
```

## İlgili

- `00-Genel-Mimari.md` · `04-zeus-service.md`
- `spring-wildfly-arch/gelistirmeler/03-wildfly-datasource.md` · `05-stored-procedure-veri-erisimi.md`
