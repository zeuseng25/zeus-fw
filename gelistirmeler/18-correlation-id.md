# 18 — Correlation ID (uçtan uca istek izleme)

Bir isteğin tüm aşamaları — REST ucu → çağrılan başka API → onun backend'i → stored
procedure → SOAP → async iş — **tek bir kimlikle** izlenebilir. Kimlik log satırlarına
otomatik basılır; uygulama kodu hiçbir şey taşımaz.

## Taşıyıcı: MDC, Maven bağımlılığı DEĞİL

Correlation ID **SLF4J MDC**'de (thread-local map) durur. MDC `slf4j-api` içindedir ve
`zeus-base` üzerinden tüm zeus modüllerinde zaten vardır.

```
Filter (zeus-logger)          MDC.put("correlationId", "abc123")
   ↓ aynı thread
Service → Repository          log.debug("SP query: PRODUCT_PKG.GET_ALL")
   ↓
Logback pattern %X{correlationId}   →  [abc123] SP query: PRODUCT_PKG.GET_ALL
```

> **`zeus-database`'e `zeus-logger` bağımlılığı EKLENMEZ.** Modüller birbirini tanımadan
> aynı kimliği loglar. Üstelik ok yanlış yöne bakardı: `zeus-logger` bir *web* modülüdür
> (servlet API'sine bağlı), `zeus-database` ise servlet konteyneri olmayan batch
> uygulamalarında da çalışmalıdır. Ortak kök `zeus-base`'tir.

## Bileşenler

| Parça | Modül | Görev |
|---|---|---|
| `CorrelationId` | `zeus-base` | MDC anahtarı, header adı, üretim + **sanitize** |
| `CorrelationIdTaskDecorator` | `zeus-base` | `@Async`/thread pool'da MDC'yi taşır |
| `CorrelationIdClientHttpRequestInterceptor` | `zeus-base` | Giden RestClient/RestTemplate çağrılarına header ekler |
| `ZeusCorrelationAutoConfiguration` | `zeus-base` | Yukarıdakileri + `RestClientCustomizer`/`RestTemplateCustomizer` kaydeder |
| `CorrelationIdFilter` | `zeus-logger` | Gelen istekte kimliği kurar/üretir, yanıt header'ına yazar, `finally`'de temizler |
| `CorrelationLoggingEnvironmentPostProcessor` | `zeus-logger` | Varsayılan log pattern (gömülü çalıştırma) |
| `ZeusServletInitializer` | `zeus-base` | Varsayılan log pattern (WAR/WildFly) — aşağıya bakınız |
| `CorrelationAwareDataSource` | `zeus-database` | Oracle `v$session.client_identifier` damgası |
| `CorrelationIdSoapInterceptors` | `zeus-soap` | CXF in/out — SOAP hattında taşır |

Header: **`X-Correlation-Id`**. Tamamı `zeus.correlation.enabled=false` ile kapatılabilir.

## Uygulama ne yapar?

Neredeyse hiçbir şey. Tek şart: WAR uygulamasının ana sınıfı `SpringBootServletInitializer`
yerine **`ZeusServletInitializer`**'ı genişletir.

```java
@SpringBootApplication
public class FooApplication extends ZeusServletInitializer { ... }
```

Kod içinde kimliğe erişmek gerekirse: `CorrelationId.get()`.

## ⚠️ ZeusServletInitializer neden var — WildFly module classloader kısıtı

Log pattern'ının doğal yolu bir `EnvironmentPostProcessor`'dır ve `META-INF/spring.factories`
ile kaydedilir. Gömülü çalıştırmada (`spring-boot:run`) sorunsuz çalışır — **ama ince WAR
modelinde WildFly'da hiç çalışmaz.**

Sebep: `spring-boot` jar'ı paylaşımlı `com.zeus` WildFly module'ündedir. `SpringFactoriesLoader`
o classloader'dan çalışır ve WAR'ın `WEB-INF/lib`'indeki `META-INF/spring.factories`
dosyalarını **göremez**. (Auto-configuration'ın `.imports` dosyaları etkilenmez; onlar
uygulama classloader'ı ile yüklenir — bu yüzden `ZeusCorrelationAutoConfiguration` WildFly'da
sorunsuz yüklenir, ama pattern gelmez.)

`ZeusServletInitializer` WAR'ın kendi sınıf hiyerarşisindedir; `SpringApplicationBuilder
.properties(...)` ile eklediği değerler loglama başlatılmadan önce environment'a girer.

**Genel kural:** ince WAR + `com.zeus` module modelinde `spring.factories` tabanlı hiçbir
Spring uzantısına (EnvironmentPostProcessor, SpringApplicationRunListener,
ApplicationContextInitializer) güvenilmez. Alternatif: `.imports` (auto-configuration) ya da
`ZeusServletInitializer`.

## Güvenlik: log injection

`X-Correlation-Id` istemci kontrolündedir. Doğrudan MDC'ye yazılsaydı satır sonu içeren bir
değer log dosyasına **sahte satır** enjekte edebilirdi. `CorrelationId.sanitizeOrGenerate()`
yalnızca `[A-Za-z0-9._-]` ve en fazla 64 karakter kabul eder; ihlal eden değer atılır ve
yerine yeni kimlik üretilir (bkz. `11-guvenlik-analizi-best-practices.md`, G-4).

## Veritabanı tarafı

`CorrelationAwareDataSource`, alınan her bağlantıya JDBC standardı
`Connection.setClientInfo("OCSID.CLIENTID", ...)` ile kimliği damgalar. Oracle bunu
`v$session.client_identifier`'a eşler:

```sql
SELECT sid, client_identifier, sql_id FROM v$session WHERE client_identifier = '<correlationId>';
```

Böylece DBA, yavaş bir sorgunun hangi HTTP isteğinden geldiğini görür. Modülde
`import oracle.*` yoktur; desteklemeyen sürücüde çağrı sessizce atlanır (bir kez uyarı
loglanır), veri erişimi asla kırılmaz. Kapatma: `zeus.correlation.datasource.enabled=false`.

### ⚠️ İki sarma yolu vardır — ikisi de gereklidir

`ZeusCorrelationDataSourceAutoConfiguration`'daki `BeanPostProcessor` yalnızca **bean olarak
tanımlanmış** `DataSource`'ları sarar. Bu, lokal modu kapsar; **JNDI modunu kapsamaz**:
`ZeusJndiDataSourceAutoConfiguration`, `StoredProcedureExecutors` için datasource'ları
`ZeusDataSources.jndi(...)` ile **bean olmadan** kurar ve uygulamanın asıl sorgu yolu
(`sp.getOracleDs()`) oradan geçer. O yüzden orada `CorrelationAwareDataSource.wrapIfNeeded(...)`
ile açıkça sarılır.

İlk uygulamada bu atlanmıştı: BeanPostProcessor kurulu, `@Primary` datasource sarılı, hiçbir
hata yok — ama `v$session` boştu, çünkü sorgular sarılmamış supplier'dan geçiyordu. Yeni bir
datasource yolu eklenirse bu iki kanaldan birine bağlanmalıdır.

### Doğrulama (Oracle üzerinde yapıldı)

`ABC` kullanıcısının önce yetkilendirilmesi gerekti (dinamik performans görünümleri
varsayılan olarak kapalıdır):

```sql
-- sysdba, PDB içinde:
ALTER SESSION SET CONTAINER=FREEPDB1;
GRANT SELECT ON sys.v_$session TO ABC;
```

Ardından art arda iki istek:

```
gönderildi: istek-1-004140  →  SID 213  ABC  istek-1-004140
gönderildi: istek-2-004141  →  SID 213  ABC  istek-2-004141
```

Aynı havuz oturumu her istekte yeniden damgalanıyor — kimlik isteğe özel, bağlantıya değil.

## En sık düşülen tuzak: thread değişimi

MDC thread-local'dır. `@Async`, `CompletableFuture` veya herhangi bir `TaskExecutor`
kullanıldığında iş başka thread'e geçer ve kimlik **sessizce kaybolur** — hata vermez,
log satırı kimliksiz basılır. `CorrelationIdTaskDecorator` bunu önler; Boot'un otomatik
yapılandırdığı `TaskExecutor` mevcut `TaskDecorator` bean'ini kullanır.

Kendi executor'ını kuran uygulama onu elle takmalıdır:

```java
executor.setTaskDecorator(new CorrelationIdTaskDecorator());
```

## Doğrulama (yapıldı)

Gömülü Tomcat (`local` profil, `logging.level.com.zeus.framework.database=DEBUG`):

```
... [uctan-uca-test-1] c.z.f.d.sp.JdbcStoredProcedureExecutor : SP query: PRODUCT_PKG.GET_ALL (cursor=P_CUR)
... [uctan-uca-test-1] c.z.f.logger.RequestLoggingFilter      : GET /spring-wildfly-arch/api/products -> 200 (235 ms)
```

Veri erişim katmanı ile istek özeti **aynı kimliği** taşıyor — zincirin kanıtı budur.

WildFly (WAR deploy):

| Test | Sonuç |
|---|---|
| Header'sız istek | Kimlik üretildi, yanıt header'ında döndü |
| `X-Correlation-Id: son-dogrulama-42` | Korundu, log satırında `[son-dogrulama-42]` |
| Injection denemesi (`%0d%0a` içeren değer) | Reddedildi, yerine yeni kimlik üretildi |

## SOAP hattı doğrulaması (yapıldı)

`zeus-soap-parent` kullanan tek kullanımlık bir test WAR'ı (`@WebService @Component("echo")`)
WildFly'a deploy edilerek doğrulandı. Servis metodu dönüş değerine `CorrelationId.get()`
koyuyor; böylece kimliğin **servis implementasyonunun içinde** görünür olduğu kanıtlanıyor.

| Test | Sonuç |
|---|---|
| `X-Correlation-Id: soap-zinciri-777` | Yanıt header'ı aynı · gövde: `correlationId=soap-zinciri-777` · log: `[soap-zinciri-777] EchoService : SOAP echo cagrildi` |
| Header'sız | `c0eb2b5cce18...` üretildi, üç yerde de aynı |
| `%0d%0a` içeren değer | Reddedildi, yeni kimlik üretildi |

**Kapsam notu:** Servlet konteynerinde CXFServlet filtre zincirinin arkasındadır, dolayısıyla
kimliği `CorrelationIdFilter` kurar ve `Inbound` interceptor erken çıkar. `Inbound`'ın asıl
değeri servlet dışı CXF taşımalarıdır. **`Outbound` interceptor (CXF istemci çağrıları)
gerçek bir çağrıyla test edilmedi** — kod yolu kaydedildi, doğrulama bekliyor.

### Test WAR'ı kurarken çıkan ince WAR yan etkisi

Test uygulamasının veritabanı ve AI kullanımı yoktu, ama iki kez deploy hatası aldı:
`Failed to determine a suitable driver class`, sonra `At least one credential source must be
specified`. Sebep paylaşımlı module'ün genişliği: `com.zeus` Hibernate/Hikari/spring-ai
jar'larını classpath'e koyduğu için Boot onları **otomatik yapılandırmaya çalışır**, uygulama
o yetenekleri kullanmasa bile.

> **Kural (GÜNCEL, 2026-09-11'den beri):** module dar değil geniştir — ama **daraltma artık
> uygulamanın değil FRAMEWORK'ün işidir.** Uygulama `spring.autoconfigure.exclude` yazmaz;
> yalnız **kullandığı** yetenekler için tek satırlık bir bildirim yazar
> (`zeus.<yetenek>.enabled=true`), kullanmadığı için hiçbir şey yazmaz. Mekanizma:
> `21-yetenek-opt-in.md`; aynı güncelleme doküman 08'de de yapılıdır.
>
> **TARİHSEL NOT — bu kutunun eski hâli** şunu söylüyordu: "uygulama, module'de olup
> kendisinin kullanmadığı yetenekleri `spring.autoconfigure.exclude` ile (ya da gereken
> minimum property'yi vererek) susturmalıdır; daraltma uygulamanın işidir." O cümle opt-in
> mekanizmasından önceki dönemi anlatır ve **artık uygulanmaz** — yukarıdaki paragrafta
> anlatılan iki deploy hatası da bugün o yolla değil, yetenek bildirimiyle çözülür.

## İzleme kanalını açmak

Veri erişim adımını görmek için:

```properties
logging.level.com.zeus.framework.database=DEBUG
```
