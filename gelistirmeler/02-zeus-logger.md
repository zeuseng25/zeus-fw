# 02 — zeus-logger

## Amaç

Projeler arası tutarlı loglama. Standart logback/SLF4J yapılandırması, istek loglama ve WildFly logging subsystem ile uyum tek modülde toplanır; her proje kendi logback'ini sıfırdan kurmaz.

## Mevcut Durum

- `RequestLoggingFilter` (`OncePerRequestFilter`) — her HTTP isteğini tek satırda loglar: `METOT /yol?query -> status (süre ms)`.
- `ZeusLoggerAutoConfiguration` — web uygulamalarında bu filtreyi **bean** olarak kaydeder (`@ConditionalOnWebApplication` + `@ConditionalOnMissingBean`). Uygulama ekstra kod yazmadan istek loglarına sahip olur ("framework log atıyor").
- `.imports` ile otomatik yüklenir. Bağımlılıklar: `spring-boot-autoconfigure`, `zeus-base`, `jakarta.servlet-api` (provided).

## Planlanan İçerik

- Ortak logback yapılandırması (desen, seviye profilleri) — `logback-spring.xml` tabanı.
- İsteğe correlation-id ekleme (MDC) ve gövde/başlık loglama seçenekleri.
- WildFly logging subsystem dışlama notu/rehberi: WAR olarak deploy edilirken JBoss Logging'in Logback/SLF4J ile çakışmaması için `jboss-deployment-structure.xml`'de `logging` subsystem ve `org.slf4j` dışlaması (bkz. kaynak doküman).

> WildFly dışlama deseni `spring-wildfly-arch/src/main/webapp/WEB-INF/jboss-deployment-structure.xml` içinde mevcut; modül bunu standart hale getirecek.

## Kullanım

```xml
<dependency>
    <groupId>com.zeus</groupId>
    <artifactId>zeus-logger</artifactId>
</dependency>
```

## İlgili

- `00-Genel-Mimari.md` · `01-zeus-base.md`
- `spring-wildfly-arch/gelistirmeler/06-wildfly-deployment.md` (logging fix kaynağı)
