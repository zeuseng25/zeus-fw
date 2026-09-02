# 11 — Güvenlik Analizi ve Best Practices (1000 uygulama perspektifi)

Bu doküman, Zeus Framework'ün (BOM + parent + modüller + paylaşımlı `com.zeus` WildFly module)
**güvenlik analizini** ve framework'ü kullanacak uygulama ekipleri için **best-practice kural
setini** içerir. Analiz, referans uygulama `../../spring-wildfly-arch` ile birlikte yapılmıştır
(uygulama tarafı bulgular: `../../spring-wildfly-arch/gelistirmeler/15-performans-guvenlik-analizi.md`).

**Kapsam notu:** Bu doküman analiz + kural setidir; kod değişikliği içermez. Düzeltmeler sondaki
[Önceliklendirilmiş Backlog](#önceliklendirilmiş-backlog) bölümünde [FW] (framework) / [APP]
(uygulama ekibi) etiketiyle listelenir. CVE *tespiti/taraması* bu dokümanın da kapsamı dışındadır
(bkz. `09-cve-guvenlik-yamalama.md` — güvenlik ekibi bildirir, platform yamalar); burada yalnızca
**riskin mimari analizi** yapılır.

## 1000 Uygulama Çarpanı: Neden Framework Güvenliği Farklıdır

Zeus'u 1000+ uygulama kullanacak. Tüm 3. parti jar'lar tek paylaşımlı `com.zeus` module'ünde,
tüm sürümler tek BOM'da (`zeus-dependencies`) olduğu için:

| Gerçek | Sonuç |
|--------|-------|
| **Framework kusuru × 1000** | Module'deki yamasız bir kütüphane, onu kullanan HER uygulamada aynı anda sömürülebilir. Tek CVE = kurum çapında olay. |
| **Framework düzeltmesi = bedava** | Tek BOM override + module yenileme, 1000 uygulamayı WAR'larına dokunmadan yamalar (bkz. doküman 09). |
| **Framework boşluğu = 1000 farklı çözüm** | Framework'ün sağlamadığı her güvenlik yeteneğini (authn, header, hata maskeleme) 1000 ekip kendi başına çözer — ya tutarsız çözer ya da **hiç çözmez** (referans uygulamada olduğu gibi). |

Bu yüzden ilke şudur: **"Secure by default"** — güvenli davranış framework varsayılanı olmalı,
uygulama ekibi ancak bilinçli ve açık bir kararla gevşetebilmeli (opt-out explicit).

## Bulgular (önem sırasına göre)

Format: **Önem / Bulgu / Dosya / Etki (×1000) / Önerilen düzeltme / Sahiplik**.

### G-1 [KRİTİK] BOM'da sıfır CVE override — EOL Spring Boot 3.1.3 yamasız

> **DURUM: ÇÖZÜLDÜ (2026-08-28).** Aşağıdaki bulgu Boot 3.1.3 taban çizgisinde yazılmıştır;
> tarihsel kayıt olarak korunuyor. Bugünkü durum: platform **Boot 4.0.7 / Java 25** hattında
> (`13-java25-boot4-wildfly41-yukseltme.md`) ve BOM artık gerekçeli CVE override'ları içeriyor
> (ör. `commons-beanutils 1.11.0` — CVE-2025-48734; `bouncycastle` jdk15on→jdk18on 1.85).
> Kapsam kararları ve yasaklı EOL listesi: `16-eol-bagimliliklar-ve-migrasyon.md`.

- **Bulgu:** `zeus-dependencies/pom.xml` yalnızca `spring-boot-dependencies:3.1.3` import'u +
  springdoc `2.2.0` + ojdbc11 `23.4.0.24.05` içerir. Doküman 09'un tanımladığı **CVE override
  bloğu fiilen boştur** — mekanizma var, hiç kullanılmamış. Spring Boot 3.1.x satırı EOL'dir ve
  upstream CVE yaması almaz (09'un "Kapsam Dışı" bölümü bunu kabul eder); Spring Framework 6.0.11
  ve 3.1.3'ün taşıdığı stok transitive set (`tomcat-embed-*` yardımcıları, `jackson-databind`,
  `snakeyaml`, `logback`, `hibernate-core`, ...) bilinen-yamasız risk sınıfındadır.
- **Dosya:** `zeus-dependencies/pom.xml` (dependencyManagement bloğu).
- **Etki (×1000):** Paylaşımlı module'deki tek yamasız jar, module'ü kullanan **tüm** uygulamalarda
  aynı anda açıktır. Bu mimarinin en büyük riski ve aynı zamanda en büyük kaldıracı budur: tek
  yama noktası.
- **Önerilen düzeltme:** (1) Güvenlik ekibinden mevcut module içeriğine karşı etkilenen
  artifact + hedef sürüm listesini al (09'daki aktör matrisi). (2) Override'ları BOM'a gir,
  `install-zeus-module.sh` ile module'ü yeniden üret, `verify-staging.sh` geçidinden geçir —
  yani 09 runbook'unu **ilk kez uçtan uca işlet** ve tatbikat olarak belgele. (3) Orta vadede:
  desteklenen bir Spring Boot satırına (3.3+/3.4+) yükseltmeyi ayrı platform işi olarak planla;
  1000 uygulama onboard olmadan yapmak, sonra yapmaktan katbekat ucuzdur.
- **Sahiplik:** [FW]

### G-2 [KRİTİK] Güvenlik taban çizgisi yok (authn/authz, header, CORS)

- **Bulgu:** Framework'te Spring Security entegrasyonu, güvenlik header'ları
  (`X-Content-Type-Options`, `Strict-Transport-Security`, `Cache-Control`, ...), CORS politikası,
  kimlik doğrulama/yetkilendirme hikâyesi **hiç yok**. `zeus-wildfly-module` aggregator'ında
  `spring-boot-starter-security` bile bulunmadığından, bir uygulama ekibi güvenlik eklemek istese
  paylaşımlı module bunu **taşımaz** (NoClassDefFoundError) — yani mevcut mimaride uygulamalar
  fiilen güvenliksiz kalmaya itilir.
- **Dosya:** `zeus-wildfly-module/pom.xml` (starter listesi), tüm zeus-* modülleri (security modülü yok).
- **Etki (×1000):** Referans uygulamanın tüm endpoint'leri anonim açık (bkz. app doküman 15 / AG-1).
  Referans böyleyse, onu kopyalayan 1000 uygulama da böyle başlar. En iyi senaryoda 1000 ekip 1000
  farklı güvenlik konfigürasyonu yazar (denetlenemez); en kötü senaryoda hiç yazmaz.
- **Önerilen düzeltme:** Yeni **`zeus-security` modülü**: (1) `spring-boot-starter-security`
  aggregator'a eklenir (module taşır hale gelir); (2) autoconfig ile varsayılan zincir gelir —
  tüm endpoint'ler authenticated, `/v3/api-docs`, `/swagger-ui/**` ve health gibi istisnalar
  property ile; güvenlik header'ları açık; CORS varsayılan kapalı, allowlist property ile;
  (3) kurumsal authn adaptör noktası (LDAP/OIDC/mTLS — kurum standardı neyse) tek yerde çözülür;
  (4) "güvenliği kapatmak" bilinçli tek property olur (`zeus.security.enabled=false`, yalnız
  lokal/dev profillerinde). Bu, "secure by default, opt-out explicit" ilkesinin uygulamasıdır.
- **Sahiplik:** [FW] (modül) + [APP] (rol/yetki kuralları uygulamaya özgüdür)

### G-3 [YÜKSEK] GlobalExceptionHandler catch-all eksik — hata detayı sızıntısı

- **Bulgu:** `GlobalExceptionHandler` yalnızca 2 istisna işler: `ResourceNotFoundException` → 404
  ve `MethodArgumentNotValidException` → 400. Diğer her şey — `DataAccessException` (ham `ORA-`
  mesajları; referans uygulamanın PL/SQL prosedürlerinde EXCEPTION bloğu da yok), NPE, cast
  hataları — konteynerin varsayılan 500 davranışına düşer. Framework `server.error.include-message`
  / `include-stacktrace` için de varsayılan belirlemez; sızıntı davranışı uygulamadan uygulamaya
  değişir.
- **Dosya:** `zeus-base/src/main/java/com/zeus/framework/base/GlobalExceptionHandler.java`.
- **Etki (×1000):** SQL/şema detayı, iç sınıf adları ve stack trace'ler istemciye sızabilir —
  saldırgana keşif malzemesi. 1000 uygulamanın hata gövdesi tutarsızlaşır (bazıları sızdırır,
  bazıları sızdırmaz).
- **Önerilen düzeltme:** zeus-base'e ekle: (1) `@ExceptionHandler(DataAccessException.class)` —
  ORA/SQL detayı **loga** (correlation id ile), istemciye jenerik 500 ProblemDetail;
  (2) `@ExceptionHandler(Exception.class)` catch-all — aynı ilke; (3) framework property
  varsayılanları: `server.error.include-message=never`, `include-stacktrace=never`,
  `include-binding-errors=never` (autoconfig ile). Not: 404/400 gövdelerindeki `ex.getMessage()`
  bilinçli tutulabilir — bu mesajlar uygulamanın kendi ürettiği "bulunamadı" metinleridir.
- **Sahiplik:** [FW]

### G-4 [YÜKSEK] RequestLoggingFilter tam query string'i loglar — token/PII log sızıntısı

- **Bulgu:** Filtre her istekte `URI?queryString` biçiminde **tam query string'i** INFO'da loglar
  (`RequestLoggingFilter.java:31-37`). Query parametresiyle taşınan her hassas veri (token,
  TCKN, e-posta, arama kriterleri...) düz metin olarak log dosyasına düşer. Ayrıca filtre düz
  `@Bean Filter` olarak kaydedilir — `FilterRegistrationBean` ile sıra (order) belirlenmemiştir;
  ileride bir güvenlik filtresi eklendiğinde loglamanın authn öncesi mi sonrası mı çalışacağı
  tanımsızdır. Aç/kapa property'si de yoktur.
- **Dosya:** `zeus-logger/src/main/java/com/zeus/framework/logger/RequestLoggingFilter.java`,
  `zeus-logger/src/main/java/com/zeus/framework/logger/ZeusLoggerAutoConfiguration.java`.
- **Etki (×1000):** 1000 uygulamanın log'u merkezî log altyapısına akacaktır; query-string PII'sı
  orada kalıcılaşır (KVKK/GDPR yüzeyi). Log dosyasına erişen herkes token/PII görür.
- **Önerilen düzeltme:** (1) Query string'i varsayılan olarak **maskele** — ya tamamen at, ya
  parametre-adı allowlist'i uygula (`zeus.logger.query-params=page,size` gibi); (2)
  `zeus.logger.enabled` ve seviye property'si; (3) `FilterRegistrationBean` ile deterministik
  order (güvenlik zincirinden sonra).
- **Sahiplik:** [FW]

### G-5 [ORTA] springdoc paylaşımlı module'de koşulsuz — prod'da açık Swagger

- **Bulgu:** `springdoc-openapi-starter-webmvc-ui` paylaşımlı module'de olduğundan her
  uygulamada `/v3/api-docs` ve `/swagger-ui/index.html` **her ortamda** açıktır; framework ne
  bir profil koşulu ne de bir varsayılan getirir. Referans uygulamada da prod-benzeri ortamda
  açık (smoke test `/v3/api-docs`'a bilinçli bağımlıdır — app doküman 14).
- **Dosya:** `zeus-wildfly-module/pom.xml` (springdoc), `zeus-dependencies/pom.xml` (2.2.0).
- **Etki (×1000):** 1000 uygulamanın tam API envanteri (endpoint'ler, şemalar, örnekler)
  anonim keşfe açık olur.
- **Önerilen düzeltme:** İki katman: (1) [FW] zeus autoconfig prod profilinde
  `springdoc.api-docs.enabled=false` + `springdoc.swagger-ui.enabled=false` varsayılanı getirir
  (property ile opt-in geri açılabilir); (2) [APP] kural: prod'da Swagger yalnızca ağ
  kısıtı/authn arkasında açılabilir. `zeus-security` (G-2) gelince alternatif: açık kalır ama
  authenticated. Smoke-test bağımlılığı için: test, api-docs kapalıysa `/api/...` sağlık
  çağrısına düşecek şekilde güncellenir (app doküman 14'e not düşüldü, bkz. app doküman 15/AG-5).
- **Sahiplik:** [FW+APP]

### G-6 [ORTA] SP executor'larında tanımlayıcı (identifier) enjeksiyon yüzeyi

- **Bulgu:** Her iki executor'da **parametre değerleri** güvenli bağlanır
  (`MapSqlParameterSource` / `setParameter` — SQL injection yok). Ancak **tanımlayıcılar** —
  `catalogName`, `procedureName`, `cursorName` — doğrulanmadan `withCatalogName()` /
  `withProcedureName()` / `createStoredProcedureQuery()`'ye geçirilir
  (`JdbcStoredProcedureExecutor.java:47-50`, `JpaStoredProcedureExecutor`). Bir uygulama bu
  adlara kullanıcı girdisi geçirirse SQL/çağrı enjeksiyonu oluşur. Referans uygulama doğru
  kullanır (adlar sabittir) ama framework bunu **zorlamaz**.
- **Dosya:** `zeus-database/src/main/java/com/zeus/framework/database/sp/JdbcStoredProcedureExecutor.java`,
  `.../sp/JpaStoredProcedureExecutor.java`.
- **Etki (×1000):** 1000 ekipten birinin "dinamik prosedür adı" kısayolu, framework API'si
  üzerinden injection açar; kusur uygulamada görünür ama yüzeyi framework taşır.
- **Önerilen düzeltme:** (1) [FW] Executor'lara savunma katmanı: tanımlayıcıları
  `[A-Za-z0-9_$#.]+` regex'iyle doğrula, uymayanı `IllegalArgumentException` ile reddet
  (Oracle identifier kuralı; maliyeti sıfıra yakın, cache key zaten var). (2) [APP] Kural:
  procedure/catalog/cursor adları **yalnızca sabit (constant) veya konfigürasyon** kaynaklı
  olur, asla istek verisinden türetilmez.
- **Sahiplik:** [FW] (doğrulama) + [APP] (kural)

### G-7 [DÜŞÜK] Script'lerde sabit geliştirici yolları

- **Bulgu:** `install-zeus-module.sh`, `slot-inventory.sh`, `verify-module-coverage.sh`
  varsayılan olarak sabit bir geliştirici yolunu (`WILDFLY_HOME=/Users/omer/...`) kullanır.
  `verify-staging.sh` bunu doğru yapar: restart içerdiği için `WILDFLY_HOME`'u zorunlu kılar.
- **Dosya:** `scripts/install-zeus-module.sh` (ve kardeş script'ler).
- **Etki (×1000):** Operasyon ekibi script'i yanlış makinede/CI'da koşarsa sessizce yanlış
  hedefe yazabilir; kurumsal kullanımda "varsayılan hedef" olmamalıdır.
- **Önerilen düzeltme:** Modül kuran/değiştiren tüm script'lerde `WILDFLY_HOME`'u zorunlu yap
  (verify-staging.sh kalıbı); dev kolaylığı isteniyorsa `.env`/`direnv` öner.
- **Sahiplik:** [FW]

## Best Practices: Framework Garantileri vs Uygulama Ekibi Kuralları

1000 ekibin aynı soruları tekrar çözmemesi için sorumluluk matrisi. "Garanti" sütunu bugünkü
değil, backlog tamamlandığındaki hedef durumu tanımlar (mevcut durum: Bulgular).

| Konu | Framework GARANTİ EDER | Uygulama ekibi YAPMALI |
|------|------------------------|------------------------|
| **CVE yaması** | BOM override + module yenileme ile tüm uygulamaları yamalar (doküman 09 süreci). Uygulama pom'unda 3. parti sürüm yazılmaz. | Platform release'inde kendi regresyonunu koşup lockstep onay verir. Acil hotfix override'ını kalıcılaştırmaz (09 kuralı). |
| **Kimlik doğrulama** | `zeus-security` varsayılan zinciri: tüm endpoint'ler authenticated, istisnalar property ile (G-2 sonrası). | Rol/yetki modelini (`@PreAuthorize`, path kuralları) kendi domain'ine göre tanımlar. Prod'da `zeus.security.enabled=false` KULLANMAZ. |
| **Hata gövdeleri** | ProblemDetail standardı; ORA/stack-trace detayını istemciye sızdırmayan catch-all (G-3 sonrası). | Kendi istisnalarını `ResourceNotFoundException` ailesine veya kendi `@ExceptionHandler`'ına bağlar; hata mesajına iç detay (SQL, sınıf adı) koymaz. |
| **İstek loglama** | Maskeli, sıralı, kapatılabilir tek satır access log (G-4 sonrası). | Hassas veriyi query string ile TAŞIMAZ (header/body kullanır); kendi loglarında PII maskeleme uygular. |
| **API dokümantasyonu** | Prod'da springdoc varsayılan kapalı / authn arkasında (G-5 sonrası). | Ortam bazlı görünürlüğü bilinçli yönetir; Swagger'ı public internete açmaz. |
| **SQL güvenliği** | Executor'lar parametreleri her zaman bind eder; tanımlayıcıları doğrular (G-6 sonrası). | Procedure/catalog/cursor adlarını sabit tutar; dinamik SQL'i PL/SQL içinde de bind değişkenle yazar. |
| **Kimlik bilgileri** | DB kimlik bilgileri WildFly datasource'ta kalır; framework hiçbir sırrı koda/BOM'a koymaz. | Repo'ya sır COMMIT ETMEZ (lokal şifreler dahil — bkz. app doküman 15/AG-2); sırlar vault/ortam değişkeninden gelir. |
| **Girdi doğrulama** | `spring-boot-starter-validation` module'de hazır; validation hataları standart 400 ProblemDetail. | Her giriş DTO'suna kısıt koyar — özellikle DB kolon uzunluğuna karşılık `@Size` (bkz. app doküman 15/AG-4). |

## Önceliklendirilmiş Backlog

| Faz | İş | Bulgu | Sahiplik |
|-----|-----|-------|----------|
| **Faz 1 (hemen)** | Güvenlik ekibiyle mevcut module envanterini çıkar, ilk CVE override setini BOM'a gir, 09 runbook'unu staging'de uçtan uca tatbik et | G-1 | [FW] |
| Faz 1 | GlobalExceptionHandler'a DataAccessException + catch-all handler, `server.error.include-*=never` varsayılanları | G-3 | [FW] |
| Faz 1 | RequestLoggingFilter query-string maskeleme + enable property + FilterRegistrationBean order | G-4 | [FW] |
| **Faz 2 (kısa vade)** | `zeus-security` modülü: starter-security aggregator'a, varsayılan authenticated zincir, header'lar, CORS iskeleti, kurumsal authn adaptörü | G-2 | [FW] |
| Faz 2 | springdoc prod varsayılanı kapalı (autoconfig) + smoke-test'lerin api-docs bağımlılığının güncellenmesi | G-5 | [FW+APP] |
| Faz 2 | Executor'lara identifier regex doğrulaması | G-6 | [FW] |
| Faz 2 | Script'lerde `WILDFLY_HOME` zorunluluğu | G-7 | [FW] |
| **Faz 3 (orta vade)** | Desteklenen Spring Boot satırına (3.3+/3.4+) yükseltme — ayrı platform projesi; sonrası override temizliği (09 "Kapsam Dışı" notu) | G-1 | [FW] |
| Faz 3 | Uygulama ekipleri: rol/yetki modelleri + prod Swagger politikası + sır taraması (pre-commit hook önerisi) | G-2, G-5 | [APP] |

## İlgili Dokümanlar

- `09-cve-guvenlik-yamalama.md` — CVE override mekanizması ve runbook (G-1'in çözüm aracı).
- `08-wildfly-module-dagitim.md` — paylaşımlı module üretimi (tek yama noktasının mimarisi).
- `01-zeus-base.md` — GlobalExceptionHandler (G-3'ün ev sahibi modül).
- `02-zeus-logger.md` — RequestLoggingFilter (G-4'ün ev sahibi modül).
- `12-performans-analizi-best-practices.md` — aynı analizin performans yüzü.
- `../../spring-wildfly-arch/gelistirmeler/15-performans-guvenlik-analizi.md` — referans uygulama bulguları (AG-x/AP-x).
