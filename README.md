# Zeus Framework (`zeus-fw`)

Spring Boot mimarisine benzeyen, çok modüllü kurumsal Java framework'ü. Uygulamalar `zeus-parent`'ı **parent** alır ve modülleri **sürüm yazmadan** kullanır; ortak altyapı (hata yönetimi, istek loglama, stored-procedure helper'ları, base CRUD service) hazır gelir.

## Yapı

```
zeus-fw/                 kök (aggregator) · parent: spring-boot-starter-parent:3.1.3 · <revision>
├─ zeus-dependencies/    BOM — tüm sürümler (spring-boot-dependencies + zeus modülleri + 3. parti)
├─ zeus-parent/          uygulama parent'ı — pluginManagement + zeus BOM import
├─ zeus-base/            GlobalExceptionHandler + ResourceNotFoundException (ProblemDetail)
├─ zeus-logger/          RequestLoggingFilter
├─ zeus-database/        StoredProcedureExecutor (JDBC, önbellekli) + JpaStoredProcedureExecutor
├─ zeus-service/         AbstractCrudService + DtoMapper
├─ zeus-redis/           (iskelet)
└─ zeus-batch/           (iskelet)
```

- **groupId:** `com.zeus` · **paket:** `com.zeus.framework.<modul>` · **Java 17 / Spring Boot 3.1.3**
- Tek sürüm: kök `pom.xml`'deki `<revision>` (flatten-maven-plugin ile çözülür).

## Kurulum

```bash
mvn clean install      # 8 artefaktı ~/.m2/repository/com/zeus/ altına kurar
```

## Kullanım (tüketen uygulama)

```xml
<parent>
    <groupId>com.zeus</groupId>
    <artifactId>zeus-parent</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <relativePath/>
</parent>

<dependency>
    <groupId>com.zeus</groupId>
    <artifactId>zeus-database</artifactId>   <!-- sürümsüz; BOM yönetir -->
</dependency>
```

Modüller `@AutoConfiguration` ile kendiliğinden devreye girer.

## Dokümantasyon

`gelistirmeler/` altında numaralı, component bazlı (Türkçe): `00-Genel-Mimari.md`, `01-zeus-base.md` … `07-bom-parent-surum-yonetimi.md`. Çalışma kuralları için `CLAUDE.md`.

Örnek/tüketen uygulama: `../spring-wildfly-arch` (WAR → WildFly 27, Oracle).
