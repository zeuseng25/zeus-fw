# 12 — Performans Analizi ve Best Practices (1000 uygulama perspektifi)

Bu doküman, Zeus Framework'ün **performans analizini** ve uygulama ekipleri için performans
**best-practice kural setini** içerir; `11-guvenlik-analizi-best-practices.md`'nin performans
yüzüdür. Referans uygulama tarafı bulgular:
`../../spring-wildfly-arch/gelistirmeler/15-performans-guvenlik-analizi.md`.

**Kapsam notu:** Analiz + kural seti; kod değişikliği yok. Düzeltmeler sondaki backlog'da
[FW]/[APP] etiketiyle listelenir.

1000 uygulama perspektifinden performansın iki çarpanı vardır:
- **Çalışma zamanı çarpanı:** Framework'ün her istekte koşan kodu (filtre, executor, tx proxy'si)
  kurumun TÜM trafiğinde koşar; buradaki 1 ms, kurum çapında binlerce çekirdek-saniyesidir.
- **Başlangıç/işletim çarpanı:** Her uygulamanın startup maliyeti; deploy pencerelerini, restart
  sürelerini (özellikle module yenileme sonrası zorunlu restart, bkz. doküman 08/09) ve kaynak
  ayak izini belirler.

## Mevcut İyi Durum (neyin doğru yapıldığı)

Bunlar korunması gereken kazanımlardır; yeni geliştirmeler bu ilkeleri bozmamalıdır:

| Kazanım | Nerede | Etki |
|---------|--------|------|
| **İnce WAR (~40 KB) + tek jar kopyası** | zeus-parent `packagingExcludes` + paylaşımlı module | Deploy saniyeler sürer; 3. parti jar'lar diskte/memory'de sunucu başına bir kez (1000 WAR'da jar tekrarı yok). |
| **SimpleJdbcCall önbelleği** | `zeus-database/.../sp/JdbcStoredProcedureExecutor.java` (`ConcurrentHashMap`, katalog+procedure+cursor anahtarı) | Oracle metadata sorgusu procedure başına yalnız ilk çağrıda; sonrası sıfır ek tur. |
| **Lazy + DCL executor'lar** | `zeus-database/.../sp/StoredProcedureExecutors.java` (volatile double-checked locking) | ReportingDS gibi ikincil kaynaklar kullanılmadıkça hiç çözülmez. |
| **Doğru transaction ayrımı** | `zeus-service/.../AbstractCrudService.java` — sınıf düzeyi `@Transactional`, okumalar `readOnly=true` | Okumalarda flush/dirty-check yükü yok; JTA altında da doğru semantik. |
| **Gövde bufferlamayan istek loglama** | `zeus-logger/.../RequestLoggingFilter.java` | Access-log maliyeti tek satır; request/response gövdesi kopyalanmaz. |

## Bulgular (önem sırasına göre)

Format: **Önem / Bulgu / Dosya / Etki (×1000) / Önerilen düzeltme / Sahiplik**.

### P-1 [YÜKSEK] OnZeusJndiCondition — koşul değerlendirmesinde canlı JNDI lookup

- **Bulgu:** Ortam tespiti (`WildFly mı, lokal mi?`) autoconfig **koşul değerlendirmesi
  sırasında gerçek JNDI lookup** yaparak çalışır (`OnZeusJndiCondition.java:22` —
  `new JndiDataSourceLookup().getDataSource(jndi)`). Koşulun olumsuzu da (lokal mod koşulu)
  aynı lookup'ı tekrar çalıştırır → her başlangıçta en az 2 lookup. Ayrıca condition
  değerlendirmesi Spring'de birden çok kez tetiklenebilir ve başarısız lookup exception-throw
  maliyeti taşır.
- **Dosya:** `zeus-database/src/main/java/com/zeus/framework/database/OnZeusJndiCondition.java`.
- **Etki (×1000):** Uygulama başına startup'ta küçük ama sabit bir maliyet; 1000 uygulamalık
  bir sunucu parkında toplu restart/deploy pencerelerinde (module yenileme sonrası restart
  zorunlu — doküman 09) birikir. Ayrıca "koşul değerlendirmesinde I/O yan etkisi" mimari olarak
  kırılgandır: JNDI sağlayıcısının yavaş cevap verdiği bir ortamda startup orantısız uzar.
- **Önerilen düzeltme:** Tespiti I/O'suz hale getir: (1) tercihen property-tabanlı açık mod
  (`zeus.database.mode=jndi|local`; JNDI lookup bean üretimine ertelenir, hata durumunda net
  mesajla fail-fast), otomatik tespit "convenience" olarak kalır; veya (2) lookup sonucunu
  `ConditionContext` üzerinden tek sefer hesaplayıp iki koşulun paylaşması. WildFly'da zaten
  deploy eden ekip ortamı bilir — sihirli tespit yerine açık konfigürasyon 1000 uygulamada
  daha teşhis edilebilirdir.
- **Sahiplik:** [FW]

### P-2 [YÜKSEK] AbstractCrudService.findAll sınırsız — sayfalama desteği yok

- **Bulgu:** `AbstractCrudService.findAll()` (`zeus-service/.../AbstractCrudService.java:46-48`)
  `doFindAll()`'un döndürdüğü **tüm listeyi** DTO'ya çevirip döner; framework API'sinde
  sayfalama (Pageable/limit-offset) kavramı hiç yok. Referans uygulamada bu,
  `PRODUCT_PKG.get_all`'un tüm tabloyu `ORDER BY id` ile dönmesi olarak somutlaşır
  (app doküman 15/AP-1).
- **Dosya:** `zeus-service/src/main/java/com/zeus/framework/service/AbstractCrudService.java`.
- **Etki (×1000):** Framework'ün sunduğu tek "listele" kalıbı sınırsız olduğu için 1000 uygulama
  da sınırsız listeler yazar. Tablolar büyüdükçe bellek/GC baskısı, yavaş yanıtlar ve tam tablo
  taramaları kurum genelinde eşzamanlı ortaya çıkar (framework kalıbı = kurum kalıbı).
- **Önerilen düzeltme:** (1) [FW] `findAll(int offset, int limit)` (veya `PageRequest` benzeri
  hafif bir tip) overload'u + `doFindAll(offset, limit)` kancası; SP tarafı için
  `OFFSET ... FETCH NEXT` kalıbının referans PL/SQL örneği (app doküman 15'e paralel).
  Varsayılan üst limit (ör. `zeus.service.max-page-size=500`) ile sınırsız istek reddedilir.
  (2) [APP] Kural: listeleme endpoint'leri `page/size` parametresi almadan yazılmaz; sınırsız
  `findAll` yalnızca küçük-sabit referans verileri (kod tabloları) için kabul edilir.
- **Sahiplik:** [FW+APP]

### P-3 [ORTA] JpaStoredProcedureExecutor her çağrıda sorgu kurar

- **Bulgu:** JPA implementasyonu her çağrıda `createStoredProcedureQuery` ile sorguyu yeniden
  kurar ve parametre tiplerini `p.getClass()` reflection'ıyla kaydeder; JDBC implementasyonundaki
  gibi bir derlenmiş-çağrı önbelleği yoktur (JPA API'sinde `StoredProcedureQuery` yeniden
  kullanılabilir değildir — bu bir kusurdan çok teknoloji sınırıdır). Referans uygulamadaki
  benchmark (`../../spring-wildfly-arch/gelistirmeler/12-repository-benchmark.md`) farkı
  ölçmek için vardır.
- **Dosya:** `zeus-database/src/main/java/com/zeus/framework/database/sp/JpaStoredProcedureExecutor.java`.
- **Etki (×1000):** `jpa` implementasyonunu seçen uygulamalar çağrı başına ek nesne kurulumu +
  reflection maliyeti öder; yüksek RPS'te ölçülebilir fark yaratır.
- **Önerilen düzeltme:** [FW] Resmî öneriyi dokümante et: **varsayılan ve tavsiye edilen yol
  `jdbc` executor'dır** (`SimpleJdbcCall` önbellekli); `jpa` yolu yalnızca JPA-özgü gereksinim
  (ör. entity mapping'li REF CURSOR) varsa seçilir. Karar verisi: app benchmark dokümanı.
- **Sahiplik:** [FW] (rehber) — uygulama seçimi [APP]

### P-4 [ORTA] Bağlantı havuzu boyutlandırma rehberi yok

- **Bulgu:** JNDI modunda havuz tamamen WildFly `standalone.xml`'dedir — framework ne bir
  boyutlandırma rehberi ne de önerilen datasource şablonu verir. Lokal modda Spring Boot'un
  varsayılan HikariCP ayarları kullanılır (ayarsız). 1000 uygulamalık parkta havuzlar
  ekip-başına-tahmin ile boyutlanacaktır; Oracle tarafında toplam session sayısı = tüm
  uygulamaların havuz toplamıdır.
- **Dosya:** (framework'te eksik içerik — `standalone.xml` şablonu/rehberi yok);
  referans: `../../spring-wildfly-arch/gelistirmeler/03-wildfly-datasource.md`.
- **Etki (×1000):** Aşırı büyük havuzlar Oracle session/process limitlerini tüketir (kurum
  çapında domino etkisi); aşırı küçükler gereksiz kuyruk bekletir. JTA/XA kullanımında havuz
  başına ek koordinasyon maliyeti vardır.
- **Önerilen düzeltme:** [FW] Platform dokümanı olarak "datasource boyutlandırma rehberi":
  başlangıç formülü (havuz ≈ çekirdek × 2, ölçümle ayarlanır), `min-pool-size`/`max-pool-size`/
  `validate-on-match`/`background-validation` önerili `standalone.xml` datasource şablonu,
  JTA notları ve "kurum toplamı Oracle limitine karşı takip edilir" kuralı (kim hangi havuzla
  geliyor — envanter). [APP] Havuzu şablondan açar, yük testine göre ayarlar, şablon dışına
  çıkarken platform ekibine bildirir.
- **Sahiplik:** [FW] (rehber/şablon) + [APP] (ayar)

### P-5 [ORTA] RequestLoggingFilter her istekte koşulsuz INFO

- **Bulgu:** Filtre her isteği INFO'da loglar; kapatma/örnekleme (sampling)/eşik (yalnız yavaş
  istekleri logla) seçeneği yoktur (`RequestLoggingFilter.java`). Maliyeti düşüktür (tek satır,
  gövde bufferlanmaz) ama sıfır değildir: string biçimleme + log appender I/O'su her istekte.
- **Dosya:** `zeus-logger/src/main/java/com/zeus/framework/logger/RequestLoggingFilter.java`.
- **Etki (×1000):** Kurum trafiğinin tamamı × 1 log satırı = merkezî log altyapısında hacim
  (depolama + ingest maliyeti) ve yüksek-RPS uygulamalarda ölçülebilir CPU. Güvenlik yüzü ayrıca
  doküman 11/G-4'te (query string PII).
- **Önerilen düzeltme:** [FW] G-4 düzeltmesiyle birlikte tek pakette: `zeus.logger.enabled`,
  `zeus.logger.slow-threshold-ms` (yalnız eşiği aşanlar INFO, kalanı DEBUG) ve örnekleme
  opsiyonu. Varsayılan davranış geriye uyumlu kalabilir; yüksek trafikli uygulamalar eşik
  moduna geçer.
- **Sahiplik:** [FW]

### P-6 [DÜŞÜK] zeus-redis iskelet — cache stratejisi tanımsız

- **Bulgu:** `zeus-redis` yalnızca "iskelet yüklendi" loglayan bir autoconfig'tir; CacheManager,
  `@Cacheable` desteği, TTL/serializer varsayılanları yoktur. Framework bir cache hikâyesi
  sunmadığı için uygulamalar ya hiç cache kullanmaz ya da kendi çözümünü kurar.
- **Dosya:** `zeus-redis/src/main/java/com/zeus/framework/redis/ZeusRedisAutoConfiguration.java`.
- **Etki (×1000):** Sık okunan referans verileri (kod tabloları vb.) her istekte Oracle'a gider;
  kurum çapında DB yükü. Ekipler kendi cache'lerini kurarsa tutarsız TTL/invalidation kalıpları
  doğar.
- **Önerilen düzeltme:** [FW] Roadmap işi: `zeus-redis`'i gerçek modüle çevir — RedisTemplate +
  CacheManager autoconfig, standart serializer, isim-alanlı key kalıbı, TTL property'leri;
  aggregator'a `spring-boot-starter-data-redis` eklenmesi (module taşımalı). [APP] Cache'lenecek
  veriyi (read-mostly) ve invalidation kuralını domain bazında belirler.
- **Sahiplik:** [FW] (modül) + [APP] (kullanım)

## Best Practices: Framework Garantileri vs Uygulama Ekibi Kuralları

"Garanti" sütunu backlog tamamlandığındaki hedef durumdur.

| Konu | Framework GARANTİ EDER | Uygulama ekibi YAPMALI |
|------|------------------------|------------------------|
| **SP çağrı maliyeti** | `jdbc` executor derlenmiş çağrıyı önbellekler; metadata turu procedure başına 1 kez. | Varsayılan `app.repository.impl=jdbc`'de kalır; `jpa`'yı yalnız gerekçeyle seçer (benchmark: app doküman 12). |
| **Listeleme** | Sayfalı `findAll` overload'u + üst limit varsayılanı (P-2 sonrası). | Listeleme endpoint'lerini page/size ile yazar; SP'lerde `OFFSET/FETCH` kullanır; sınırsız listeyi yalnız küçük sabit veri için kullanır. |
| **Transaction** | CRUD tabanında doğru `readOnly` ayrımı; JTA entegrasyonu hazır. | Servis metodunu kısa tutar (tx içinde dış servis çağrısı/dosya I/O yapmaz); uzun raporlamayı ReportingDS'e ayırır. |
| **Startup** | Autoconfig koşullarında I/O yok (P-1 sonrası); modüller lazy. | Ağır init işlerini (cache ısıtma vb.) lazy/async yapar; startup'ta senkron dış çağrı eklemez. |
| **İstek loglama** | Düşük maliyetli, eşik/örnekleme destekli access log (P-5 sonrası). | Kendi kodunda istek başına ek INFO log satırları çoğaltmaz; DEBUG'ı prod'da kapalı tutar. |
| **Havuz** | Onaylı `standalone.xml` datasource şablonu + boyutlandırma rehberi (P-4 sonrası). | Şablondan başlar, yük testiyle ayarlar; havuz büyütmeden önce sorguyu/SP'yi iyileştirir. |
| **Cache** | Redis tabanlı standart cache altyapısı (P-6 sonrası). | Read-mostly veriyi TTL'li cache'ler; cache'i doğruluk gerektiren veride senkronizasyonsuz kullanmaz. |
| **SQL loglama** | — (uygulama ayarı) | Prod'da `spring.jpa.show-sql=false` (referans uygulamadaki hata: app doküman 15/AG-3); SQL izleme gerekirse merkezî APM kullanılır. |

## Önceliklendirilmiş Backlog

| Faz | İş | Bulgu | Sahiplik |
|-----|-----|-------|----------|
| **Faz 1 (hemen)** | Ortam tespitini I/O'suz yap: property-tabanlı mod + bean üretimine ertelenen JNDI lookup | P-1 | [FW] |
| Faz 1 | "jdbc executor varsayılandır" resmî rehber notu (03-zeus-database + 04-zeus-service dokümanlarına) | P-3 | [FW] |
| **Faz 2 (kısa vade)** | AbstractCrudService'e sayfalı findAll + üst limit; SP tarafı OFFSET/FETCH referans kalıbı | P-2 | [FW] |
| Faz 2 | RequestLoggingFilter: enabled/eşik/örnekleme property'leri (11/G-4 ile aynı pakette) | P-5 | [FW] |
| Faz 2 | Datasource boyutlandırma rehberi + onaylı standalone.xml şablonu | P-4 | [FW] |
| Faz 2 | Uygulamalar: listeleme endpoint'lerine sayfalama, prod'da show-sql kapalı | P-2 | [APP] |
| **Faz 3 (orta vade)** | zeus-redis'i gerçek cache modülüne çevir (starter aggregator'a, CacheManager, TTL standartları) | P-6 | [FW] |
| Faz 3 | Kurum çapında havuz envanteri + Oracle session bütçesi takibi | P-4 | [FW+APP] |

## İlgili Dokümanlar

- `03-zeus-database.md` — executor'lar ve datasource autoconfig (P-1, P-3'ün ev sahibi).
- `04-zeus-service.md` — AbstractCrudService (P-2'nin ev sahibi).
- `08-wildfly-module-dagitim.md` / `09-cve-guvenlik-yamalama.md` — restart/deploy pencereleri (startup maliyetinin işletim bağlamı).
- `11-guvenlik-analizi-best-practices.md` — aynı analizin güvenlik yüzü (G-4 ↔ P-5 aynı filtre).
- `../../spring-wildfly-arch/gelistirmeler/12-repository-benchmark.md` — jdbc vs jpa ölçümü (P-3 kanıtı).
- `../../spring-wildfly-arch/gelistirmeler/15-performans-guvenlik-analizi.md` — referans uygulama bulguları (AP-x).
