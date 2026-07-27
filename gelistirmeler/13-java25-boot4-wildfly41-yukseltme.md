# 13 — Java 25 + Spring Boot 4.0.7 + WildFly 41 Yükseltmesi (platform)

Zeus Framework **2.0.0** platform sürümü: Java 17→**25**, Spring Boot 3.1.3→**4.0.7**
(Spring Framework 6.0→**7.0**, Hibernate 6.2→**7.2**, Jackson 2→**2+3**), WildFly 27→**41**
(EE 10→**EE 11**, Servlet 6.1). Uygulama tarafındaki değişiklikler:
`../../spring-wildfly-arch/gelistirmeler/16-java25-boot4-wildfly41-yukseltme.md`.

## Neden 2.0.0 (KIRICI platform sürümü)

Boot 4'te starter'lar modülerleşti ve **adları değişti** — bu, zeus-parent'ı kullanan her
uygulamanın pom'una yansır (aşağıdaki tablo). Semver gereği major sürüm atlandı:
`revision` 1.0.0-SNAPSHOT → **2.0.0-SNAPSHOT**. Uygulamalar parent sürümünü yükseltirken
pom'larındaki starter adlarını da değiştirmek zorundadır.

| Boot 3.1 (zeus 1.x) | Boot 4 (zeus 2.x) | Not |
|---------------------|-------------------|-----|
| `spring-boot-starter-web` | `spring-boot-starter-webmvc` | Uygulama pom'unda değişir |
| `spring-boot-starter-tomcat` (provided) | `spring-boot-starter-tomcat` (provided) | AYNI kalır — Boot 4'te de webmvc, tomcat starter'ını çeker; provided ile WAR/module dışı kalır |
| `ojdbc11` | `ojdbc17` | BOM yönetir (23.9.0.25.07, JDK 17–25) |
| springdoc 2.2.0 | springdoc **3.0.3** | 2.x Boot 4 ile ÇALIŞMAZ |

## Framework'te yapılan değişiklikler

- **`pom.xml` (kök):** parent `spring-boot-starter-parent` 3.1.3→4.0.7; `java.version=25`;
  **`spring-framework.version=6.0.11` pini SİLİNDİ** — Boot 4 BOM'u Framework 7.0.x'i yönetir.
  (CVE gerektiğinde override yeri her zaman `zeus-dependencies` BOM'udur; kök pom'da pin tutulmaz.)
- **`zeus-dependencies` (BOM):** spring-boot-dependencies 4.0.7 import; springdoc 3.0.3; ojdbc17.
- **`zeus-database`:** Boot 4 autoconfig modülerleşmesi — `DataSourceAutoConfiguration`
  artık `org.springframework.boot.jdbc.autoconfigure` paketinde (`spring-boot-jdbc` modülü),
  `HibernateJpaAutoConfiguration` `org.springframework.boot.hibernate.autoconfigure`'da
  (`spring-boot-hibernate`). İki modül pom'a derleme bağımlılığı olarak eklendi, import'lar
  güncellendi. **Diğer tüm zeus kaynak kodu değişmeden derlendi** (SimpleJdbcCall,
  StoredProcedureQuery, ProblemDetail, OncePerRequestFilter, JndiDataSourceLookup —
  hepsi Framework 7'de stabil).
- **`zeus-wildfly-module`:** `starter-web`→`starter-webmvc`. `starter-tomcat` provided kalıbı
  Boot 4'te de gerekli (webmvc hâlâ tomcat çekiyor); provided ile tüm alt ağacı
  (tomcat-embed-core/websocket, spring-boot-tomcat) kapanıştan düşer. `tomcat-embed-el`
  bilinçli KALIR (starter-validation üzerinden; hibernate-validator'ın EL implementasyonu).
- **`zeus-war-defaults` (descriptor şablonu):** `org.slf4j` dışlaması KALDIRILDI —
  WildFly 41'de o module artık yok, dışlama her deploy'da WFLYSRV0274 uyarısı üretiyordu.
  Subsystem dışlamaları (logging/weld/batch-jberet/jsf/jaxrs) ve `urn:1.3` şeması WF41'de
  aynen geçerli.
- **`scripts/install-zeus-module.sh`:** iki düzeltme:
  1. **Jandex artık ARAÇ olarak** Maven'dan çözülür (`io.smallrye:jandex:3.2.0`) — Hibernate 7
     kapanışında jandex jar'ı yok (yerine `hibernate-models`), eski "module içindeki jandex'i
     kullan" varsayımı kırılmıştı.
  2. module.xml jakarta bağımlılıklarına **`jakarta.json.api` + `jakarta.json.bind.api`**
     eklendi — Boot 4 http-converter autoconfig'inin `@ConditionalOnClass(Jsonb)`
     introspection'ı tip görünmeyince her boot'ta WARN üretiyordu.
- **Script varsayılanları:** `WILDFLY_HOME` → `/Users/omer/workspaces/intellij/wildfly-41/wildfly-41.0.0.Final`.

## Jackson 2 + Jackson 3 birlikte yaşama (module içeriği)

Boot 4 Jackson 3 kullanır (`tools.jackson` groupId, `tools.jackson.*` paketleri);
`jackson-annotations` ise `com.fasterxml.jackson.core`'da kalır. springdoc/swagger-core hâlâ
Jackson 2 kullandığından paylaşımlı module'de **her iki nesil birlikte** bulunur
(jackson-core/databind 2.21.4 VE jackson-core/databind 3.1.4). Paket adları farklı olduğu
için çakışma/LinkageError YOKTUR — bu bir hata değil, beklenen durumdur; module üretiminde
"duplicate jackson" diye dışlama YAPILMAZ.

## Module içeriği (78 jar) ve doğrulama

`copy-dependencies` kapanışı 85 bağımlılık çözer; 7 `jakarta.*-api` jar'ı dışlanır → 78 jar.
Öne çıkan sürümler: Spring 7.0.8, Boot 4.0.7 (modüler: spring-boot-jdbc/hibernate/jackson/
servlet/webmvc...), Hibernate ORM 7.2.19, hibernate-validator 9.0.1, HikariCP 7.0.2,
snakeyaml 2.5, logback 1.5.34. **Yan kazanım:** EOL 3.1.3 satırının bilinen-yamasız transitive
seti (doc 11/G-1) desteklenen güncel satırla değişti; G-1 riski büyük ölçüde kapandı.
Boot 4 satırı upstream CVE yaması aldığı sürece doc 09'daki override mekanizması yine geçerli
ama "EOL çaresizliği" ortadan kalktı.

## WildFly 41 ortamı (WF27'ye dokunulmadı — rollback)

- Kurulum: `/Users/omer/workspaces/intellij/wildfly-41/wildfly-41.0.0.Final` (standart EE 11
  dağıtımı). WF27 kurulumu, module'ü ve config'i **aynen yerinde** — rollback = WF27'yi başlatmak.
- JDK 25 (Homebrew `openjdk@25`, 25.0.4) ile çalışır; WF41 SE 25'i destekler/önerir.
- `com.oracle.ojdbc` module'ü ojdbc17 ile yeniden oluşturuldu (ad aynı → uygulama wiring'i
  değişmedi); `OracleDS`/`ReportingDS` datasource'ları jboss-cli ile sıfırdan tanımlandı
  (WF27 standalone.xml'i KOPYALANMADI), `test-connection-in-pool` iki DS için de yeşil.
- Admin: `add-user.sh` ile aynı kimlik.

## Doğrulama sonuçları (uçtan uca)

| Kapı | Sonuç |
|------|-------|
| `mvn clean install` (zeus-fw, JDK 25) | ✅ |
| Module üretimi + içerik denetimi (tomcat-embed yok, jakarta-api yok, Jandex gömülü) | ✅ 78 jar |
| App ince WAR (yalnız zeus-2.0.0 jar'ları, ~51 KB) | ✅ |
| `verify-module-coverage.sh` | ✅ tam kapsam |
| WF41 deploy + `smoke-test.sh` (jdbc impl) | ✅ 9/9 |
| WF41 deploy + `smoke-test.sh` (jpa impl) | ✅ 9/9 |
| Lokal profil (embedded Tomcat, Java 25, ojdbc17 → Docker Oracle CRUD) | ✅ |
| `spring-boot-properties-migrator` raporu | ✅ temiz (eski property yok) |
| server.log: ERROR/LinkageError/Jackson çakışması | ✅ 0 |

## İlgili Dokümanlar

- `07-bom-parent-surum-yonetimi.md` — revision/BOM mekanizması (2.0.0 burada değişti).
- `08-wildfly-module-dagitim.md` — module üretimi (Jandex/jakarta değişiklikleri buraya işlendi).
- `09-cve-guvenlik-yamalama.md` — override mekanizması (artık desteklenen satır üzerinde).
- `11-guvenlik-analizi-best-practices.md` — G-1 (EOL) bulgusunun kapanışı.
- `../../spring-wildfly-arch/gelistirmeler/16-java25-boot4-wildfly41-yukseltme.md` — uygulama tarafı.
