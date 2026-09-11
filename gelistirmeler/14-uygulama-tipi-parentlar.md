# 14 — Uygulama Tipine Özel Parent'lar (zeus-soap-parent + zeus-bff-parent)

Zeus artık tek tip uygulama desteklemiyor: uygulama TİPİNE göre farklı build/paketleme
davranışı veren üç parent var. Hepsi aynı reaktörde, **tek `${revision}`** (2.0.0-SNAPSHOT)
ile sürümlenir — hangi parent kullanılırsa kullanılsın versiyon TEKTİR.

## Tip → Parent Matrisi

| Tip | Parent | Paketleme | WildFly module bağımlılığı | Zeus jar modülleri |
|---|---|---|---|---|
| **Standart REST** | `zeus-parent` | ince WAR (~50 KB) | `com.zeus` | zeus-base/logger/database/service |
| **SOAP** (Apache CXF) | `zeus-soap-parent` | ince WAR | `com.zeus` **+ `com.zeus.soap`** | + `zeus-soap` |
| **BFF** (gateway + React) | `zeus-bff-parent` | **FAT WAR** (~23 MB) | **YOK** (izole) | + `zeus-bff-starter`, `zeus-bff-login` |
| **Standalone** (izolasyon gerektiren servis) | `zeus-standalone-parent` | **SELF-CONTAINED WAR** | **YOK** (izole) | ihtiyaca göre |

## Her tip için çalışan örnek proje

Her parent tipinin `zeus-fw` ile aynı dizinde, deploy edilip doğrulanmış bir örneği vardır:

| Tip | Örnek proje | WAR |
|---|---|---|
| Standart REST | `../spring-wildfly-arch` | ince, ~79 KB |
| SOAP | `../zeus-sample-soap` | ince, 3 zeus jar + `com.zeus` & `com.zeus.soap` |
| BFF | `../zeus-sample-bff` | **fat**, ~24 MB, module yok |
| Standalone | `../zeus-sample-standalone` | **self-contained**, ~16 MB, module yok |

Her birinin `README.md`'si ne gösterdiğini, çalıştırma adımlarını ve o tipe özgü tuzakları
anlatır. Yeni bir uygulama açarken en yakın örneği kopyalamak, parent'ı elle kurmaktan
güvenlidir (ör. `src/main/webapp/` dizini olmadan `zeus-generated-descriptor` profili
devreye girmez ve descriptor hiç üretilmez).

Kalıtım zinciri: `zeus-soap-parent`, `zeus-bff-parent` ve `zeus-standalone-parent` → `zeus-parent` → `zeus-fw` →
`spring-boot-starter-parent`. BOM import'u, lombok, repackage-skip, flatten ve üretilen
descriptor mekanizması miras alınır; yalnız farklar override edilir.

## Jar modülleri tip parent'larına BAĞLI DEĞİLDİR (bilinçli tasarım)

`zeus-soap`, `zeus-bff-starter`, `zeus-bff-login` jar modüllerinin Maven parent'ı
`zeus-parent`'tır — `zeus-soap-parent`/`zeus-bff-parent` DEĞİL (tıpkı zeus-redis/batch gibi).
Nedeni: tip parent'ları yalnızca **uygulamalar** içindir; tek işleri WAR paketleme
davranışını seçmektir (descriptor dizini, packaging-excludes). Jar modülleri WAR üretmez,
`zeus-generated-descriptor` profili onlarda hiç devreye girmez; ihtiyaçları olan BOM +
lombok + flatten zaten `zeus-parent`'tan gelir. Özet ayrım:

> **Tip parent'ı = "uygulaman NASIL paketlenir"** · **jar modülü = "uygulamana HANGİ yetenek girer"** —
> ikisi arasındaki bağlantıyı Maven kalıtımı değil, descriptor + WildFly module mekanizması kurar.

**Yanlış kombinasyon koruması:** standart (zeus-parent) bir uygulamaya yanlışlıkla
`zeus-soap` bağımlılığı eklenirse, CXF bağımlılıkları com.zeus module'ünde bulunamaz ve
`verify-module-coverage.sh` bunu deploy'dan ÖNCE build aşamasında kırmızıyla yakalar
(çözüm mesajı: SOAP kullanılacaksa parent `zeus-soap-parent`'a geçilir).

## Mekanizma: property ile tip seçimi

`zeus-parent` iki property'yi genelleştirir; tip parent'ları yalnız bunları override eder:

| Property | zeus-parent (standart) | zeus-soap-parent | zeus-bff-parent | zeus-standalone-parent |
|---|---|---|---|---|
| `zeus.descriptor.dir` | `descriptor-standard` | `descriptor-soap` | `descriptor-bff` | `descriptor-standalone` |
| `zeus.war.packaging-excludes` | ince WAR regex'i | (miras — ince WAR) | **BOŞ** (fat WAR) | **BOŞ** (self-contained) |

Descriptor şablonları `zeus-war-defaults/src/main/resources/` altında tip başına ayrı
dizindedir; `zeus-generated-descriptor` profili `${zeus.descriptor.dir}`'i unpack edip
WEB-INF'e filtreleyerek koyar. (XML fragment'i property'ye gömme yaklaşımı bilinçli
REDDEDİLDİ — okunmaz/kırılgandır; ayrı dosyalar diff'lenebilir.)

- **descriptor-soap** farkları: `<subsystem name="webservices"/>` dışlaması (WildFly'ın kendi
  JBossWS/CXF'i deployment'a karışmasın — sınıf çakışması önlenir) + `com.zeus.soap` module
  bağımlılığı (`${zeus.soap.module.slot}` ile, varsayılan main).
- **descriptor-bff** farkları: `<dependencies>` bloğu YOK (com.zeus kullanılmaz); subsystem
  dışlamaları standartla aynı.

## Standalone hattı — ne zaman seçilir

`zeus-standalone-parent`, `com.zeus` module'üne **hiç bağlanmayan** self-contained WAR üretir.
Tüm runtime kapanışı (zeus-* jar'ları dahil) `WEB-INF/lib`'dedir; descriptor'da `com.zeus`
bağımlılığı yoktur, subsystem dışlamaları kalır.

**Seçim kriteri — üçü de doğruysa standalone'a geç:**

1. Uygulamanın kütüphaneleri paylaşımlı module'e **girmemeli** (yalnız o uygulama kullanıyor
   ve module'e konsa tüm uygulamalara dayatılırdı — bkz. `08`'deki birleşim kuralı).
2. Uygulama kendi sürümlerini **platform slot rollout'undan bağımsız** yamalayabilmeli
   (tipik olarak güvenlik bileşenleri: auth/authorization server).
3. Kısmi çözüm yetmiyor: ince WAR listesinden birkaç artefaktı elle geri almak (bir dönem
   `zeus.war.keep` property'sinin yaptığı iş; o property **kaldırıldı**, bkz.
   `19-war-paketleme-module-farkindaligi.md`) Spring ailesinin bir kısmını WAR'da bir kısmını
   module'de bırakır → **classloader bölünmesi**.

Üçüncü madde teorik değil, ölçülmüş bir kısıttır: `com.zeus` module classloader'ı WAR'ın
`WEB-INF/lib`'ini göremez. Bu yüzden module'deki `spring-boot`, WAR'daki
`META-INF/spring.factories`'i bulamaz ve `EnvironmentPostProcessor` hiç çalışmaz
(bkz. `18-correlation-id.md` → "ZeusServletInitializer neden var"). Kısmi bundling bu
sınırın yanlış tarafında kalır; standalone'da ise böyle bir bölünme yoktur.

**Bedeli:** her standalone WAR kendi Spring yığınını taşır (~16 MB) ve kendi heap'ine
yükler. CVE yaması module rollout'u yerine "BOM'da sürüm bump → WAR rebuild → redeploy"
olur — sürüm kaynağı yine tek (`zeus-dependencies`), yalnız dağıtım kanalı farklıdır.
Bu yüzden **varsayılan değildir**: yeni uygulamalar `zeus-parent` ile başlar, standalone
bilinçli ve gerekçeli bir seçimdir.

**BFF ile ilişkisi:** ikisi de izoledir ve bugün descriptor içerikleri aynıdır, ama
şablonları (`descriptor-bff` / `descriptor-standalone`) **ayrı tutulur**. Gerekçeleri
farklıdır (BFF: gateway + fat WAR tercihi; standalone: classloader izolasyonu ihtiyacı) ve
birinde yapılacak bir değişikliğin diğerine sessizce dayatılmaması gerekir.

**Kapsam denetimi:** `verify-module-coverage.sh`, `zeus.war.packaging-excludes` boşsa
denetimi atlar ve bunu ekrana yazar. Kontrol parent adına değil **politika property'sine**
bakar; böylece her izole tip (BFF dahil) otomatik kapsanır.

## Neden `zeus-parent-base` ara katmanı YOK (2026-09-02 kararı)

Tip parent'larının hepsi `zeus-parent`'tan türer — kökten (`zeus-fw`) değil. İzole tipler
(`bff`, `standalone`) `zeus-parent`'ın ince WAR politikasını ezdiği için "politikayı ortak
atadan çıkarıp bir `zeus-parent-base`'e taşıyalım" önerisi düzenli olarak gündeme geliyor.
**Bugün için reddedildi**; gerekçe aşağıda, ki aynı tartışma sıfırdan başlamasın.

### Kökten türetmek neden olmaz

`zeus-fw` kökü yalnızca şunları verir: `spring-boot-starter-parent`, `revision`,
`java.version`, `oracle-database.version`, encoding, flatten. Asıl altyapı `zeus-parent`'ın
~270 satırındadır: **zeus BOM import'u**, `spring-boot-starter-test`, pluginManagement
(compiler/lombok, repackage-skip, surefire + Mockito agent, dependency-plugin) ve en
kritiği **`zeus-generated-descriptor` profili** — descriptor'ı üreten makine. İzole tipler
descriptor'ı *farklı şablondan* ama *aynı mekanizmayla* üretir.

Kökten türeyen bir tip parent'ı bunların ~200 satırını kopyalamak zorunda kalırdı.
50+ uygulamalı bir platformda kopyalama, sürüm/politika drift'inin başladığı yerdir:
ör. Mockito javaagent düzeltmesi iki parent'ta ayrı ayrı yapılıp sonsuza kadar senkron
tutulmalıydı.

### Base ayrımı neden (henüz) gerekli değil

Mekanizma/politika ayrımı **zaten yapılmış** — property indirection'ı tam olarak bunun için
var. `zeus-parent`'ta tipe özel olan yalnızca **4 property tanımı**:

| Property | Kime ait |
|---|---|
| `zeus.war.packaging-excludes` | ince WAR (değeri ÜRETİLİR — `scripts/generate-war-excludes.sh`) |
| `zeus.module.slot` | com.zeus'a bağlanan tipler |
| `zeus.soap.module.slot` | SOAP tipinin KENDİ `com.zeus.soap` bağımlılığı için (platform sabiti) |
| `zeus.descriptor.dir` | standart tip varsayılanı |

> `zeus.war.packaging-excludes.with-soap` bu tabloda ARTIK YOK: 2026-09-09'da `zeus-sms` için
> eklenmiş, standart tipte kalıp `com.zeus.soap`'ı **opt-in** eden uygulamalar içindi.
> 2026-09-11'de CXF `com.zeus`'a taşınınca bu opt-in mekanizmasının **kendisi**
> (`zeus.descriptor.extra.modules` dahil) tamamen kaldırıldı; property artık pom'larda hiç
> geçmiyor. Detay: `20-zeus-sms.md`, `19-war-paketleme-module-farkindaligi.md`.

> `zeus.war.keep` bu tabloda ARTIK YOK: denylist polaritesine geçişte kaldırıldı. Bugün
> module'de olmayan bir bağımlılık zaten WAR'da taşınır, dolayısıyla "bundle istisnası"
> yazacak bir şey kalmadı (`19-war-paketleme-module-farkindaligi.md`). Kalıntı bırakılmadığını
> `scripts/test-no-war-keep.sh` guard'ı denetler.

Geri kalan her şey bu property'leri **okur**, değerlerini varsaymaz
(`<packagingExcludes>${zeus.war.packaging-excludes}</packagingExcludes>`, profilde
`${zeus.descriptor.dir}`). Base ayrımı bu ayrımı iyileştirmez; yalnızca 5 varsayılanı
bir seviye aşağı taşır. Kazanç kavramsal, risk platform-geneldir (herkesin miras aldığı
pom'da 250 satırlık taşıma + zincire yayınlanan bir artefakt daha).

Ölü mirasın yol açtığı **tek fonksiyonel sorun** kapsam denetimiydi ve çözüldü:
`verify-module-coverage.sh` artık parent adına değil `zeus.war.packaging-excludes`'a bakıyor
— bu, isimlendirme kuralını değil paketlemenin fiili durumunu okuduğu için daha sağlam.

### Bunun yerine yapılan: ölü property'leri açıkça boşaltmak

`zeus-bff-parent` ve `zeus-standalone-parent`, miras aldıkları ama kullanmadıkları
property'leri boşaltır:

```xml
<zeus.module.slot/>              <!-- bu tip com.zeus'a bağlanmaz -->
<zeus.war.packaging-excludes/>   <!-- self-contained WAR: hiçbir jar dışlanmaz -->
```

(Bu blokta bir dönem `<zeus.war.keep/>` de vardı; property kaldırılınca boşaltılacak bir şey
de kalmadı.) `zeus.war.packaging-excludes`'un BOŞ olması aynı zamanda
`verify-module-coverage.sh`'ın "self-contained WAR" ölçütüdür — aşağıdaki "Kapsam denetimi"
notuna bakın.

Boşaltılmazsa `help:effective-pom` çıktısında `slot: main` görünür ve okuyan kişi
uygulamanın module'e bağlandığını sanır. Risk sıfırdır: `descriptor-bff` ve
`descriptor-standalone` şablonları bu property'lere hiç referans vermez
(`descriptor-standard` 3, `descriptor-soap` 1, izole şablonlar **0** kez kullanır).

### Kararı yeniden açacak tetikleyiciler

Şunlardan biri gerçekleşirse base ayrımı kazanılmış olur ve yapılmalıdır:

1. **4. tip parent** ekleniyor ve o da izole (tiplerin çoğunluğu politikayı ezer hale gelir).
2. `zeus.module.slot` / `zeus.war.packaging-excludes` mirası **yeni bir yerde** yanlış
   davranışa yol açıyor.
3. Base seviyesinde, **standart tipe uygulanmaması gereken** bir yapılandırma ihtiyacı doğuyor.

## SOAP hattı (Apache CXF 4.2.x)

- **Neden CXF 4.2.x:** Jakarta EE 11 + Spring Framework 7 + Boot 4 uyumlu ilk satır.
  Sürüm BOM'da: `cxf.version` + `cxf-spring-boot-starter-jaxws` yönetimi.
- **`zeus-soap` modülü:** CXF starter'ın kurduğu Bus/CXFServlet üstüne
  `ZeusSoapEndpointRegistrar` — `@WebService` işaretli bean'leri `/services/<beanAdı>`
  altında otomatik yayınlar. Uygulama yalnızca `@WebService @Component` sınıf yazar.
- **`com.zeus.soap` WildFly module'ü — BUGÜN BOŞ (0 jar, 2026-09-11'den beri).** Sözleşmesi
  `zeus-soap-wildfly-module`; üretimi `./scripts/install-zeus-module.sh --module soap [--slot X]
  [--base-slot Y]`. Jar seti = CXF kapanışı **EKSİ** com.zeus kapanışı (**küme farkı** — script
  temel kapanışı da çözüp aynı ada sahip jar'ları atlar; çift jar/LinkageError imkânsızlaşır.
  Provided hilesi kullanılmadı: CXF, Spring jar'larını kendi compile yolundan da çektiği için
  nearest-wins belirsiz olurdu). **2026-09-08'de** 23 jar taşıyordu (cxf-core/rt-*, wsdl4j,
  woodstox, xmlschema, neethi...); **2026-09-11'de** CXF `com.zeus`'un KENDİ kapanışına
  taşındığından küme farkı **∅**'dir — SOAP tipi hâlâ bu module'ü import eder (aşağıdaki
  "Kapsam denetimi" ve `descriptor-soap` şablonu değişmedi) ama artık içi boştur; module.xml
  `com.zeus`'a (base-slot) ve `jakarta.xml.ws/soap/bind/...` server API'lerine bağımlıdır.
  Boş bir module'ün kendi Jandex index'i olmadığı için WildFly'ın deploy zamanı OOM'a
  düşmemesi ayrı bir düzeltme gerektirdi (`empty-index/` — bkz.
  `08-wildfly-module-dagitim.md` → "com.zeus.soap — artık BOŞ bir module").
- **Kapsam denetimi:** `verify-module-coverage.sh`, uygulamanın **tipinin** SOAP olup
  olmadığına bakıyor (üretilen descriptor `com.zeus.soap`'ı fiilen import ediyor mu —
  `DESC_SOAP`). Öyleyse denetimi **com.zeus ∪ com.zeus.soap birleşimine** karşı yapar.
  ESKİDEN (opt-in mekanizması varken) burada AYRICA dışlama listesinde `cxf-core` aranarak
  standart tipin de opt-in etmiş olabileceği bir ikinci iz tutulurdu; CXF `com.zeus`'a
  taşınınca standart listede de doğal olarak `cxf-core` belirmeye başladığından bu ikinci iz
  HER ince WAR'ı "SOAP kullanıyor" sayardı — yanlış pozitif. Bu yüzden tamamen kaldırıldı;
  ölçüt artık TEK ve doğrudan: descriptor'ın kendisi.

## BFF hattı (Spring Cloud Gateway Server MVC)

- **Neden reactive DEĞİL:** Reaktif Spring Cloud Gateway (WebFlux/Netty) servlet
  konteynerine WAR olarak deploy EDİLEMEZ. BFF'ler WildFly'da çalışacağı için servlet
  tabanlı **Gateway Server MVC** (`spring-cloud-starter-gateway-server-webmvc`) kullanılır;
  route/filter modeli işlevsel eşdeğerdir. Spring Cloud train: **2025.1.x (Oakwood)** —
  Boot 4 uyumlu; BOM'a `spring-cloud-dependencies` import'u eklendi (Boot BOM'undan SONRA —
  örtüşen yönetimde ilk import [Boot] kazanır, bilinçli tercih).
- **Neden FAT WAR:** BFF izoledir — com.zeus module'ünün sürüm/slot/restart yaşam
  döngüsünden bağımsız deploy edilir; tüm jar'lar WAR içindedir (gömülü Tomcat hariç —
  `spring-boot-starter-tomcat` provided). "Fat WAR yasak" kuralının tek istisnası budur ve
  uygulama kararı değil FRAMEWORK kararıdır (zeus-bff-parent).
- **`zeus-bff-starter`:** SPA sunumu — React build çıktısı `classpath:/static`'e konur;
  uzantısız/dışlanmamış GET'ler `index.html`'e düşer (SPA fallback). Dışlama PREDICATE'te
  yapılır: eşleşmeyen istek sıradaki RouterFunction'a (gateway route'ları) devredilir.
  Uygulama, gateway öneklerini `zeus.bff.spa-fallback.exclude-prefixes`'e ekler
  (varsayılan `/api/`); kapatma: `zeus.bff.spa-fallback.enabled=false`.
  Filter uzantı noktası: `ZeusBffFilter` (HandlerFilterFunction).
- **`zeus-bff-login`:** İSKELET — `LoginFilterHook`/`SessionHook` arayüzleri; gerçek authn
  kurum kimlik sağlayıcısı netleşince bu modülde geliştirilecek.
- **WAR context path tuzağı:** gateway path işlemleri context path'i İÇERİR — ör.
  `/bff-app` context'inde `Path=/proxy/**` route'unda `StripPrefix=2` gerekir
  (1 = context segmentini keser). Doğrulanmış örnek aşağıda.

## Uygulama ekipleri için kurallar

1. **Parent seçimi tip seçimidir**: REST → zeus-parent, SOAP → zeus-soap-parent,
   BFF → zeus-bff-parent. Paketleme/descriptor davranışını parent belirler; uygulama
   war-plugin/descriptor override ETMEZ.
2. **Yetenek bildirimi — kullandığın yeteneği YAZ, kullanmadığını YAZMA** (2026-09-11'den
   beri). Uygulama artık `spring.autoconfigure.exclude` yazmaz; kullandığı her zeus yeteneği
   için **tek satır** yazar:
   ```properties
   zeus.soap.enabled=true        # SOAP endpoint yayınlayan uygulama (zeus-soap)
   zeus.database.enabled=true    # veritabanı kullanan uygulama (zeus-database)
   zeus.ai.enabled=true          # AI kullanan uygulama (zeus-ai)
   ```
   Kullanılmayan yetenek için **hiçbir şey yazılmaz** — o yeteneğin 3. parti autoconfig'leri
   (JDBC/Hibernate/JPA, Spring AI, CXF) aday listesine hiç girmez. Daraltma artık uygulamanın
   değil **framework'ün** işidir; mekanizma `21-yetenek-opt-in.md`'de.

   **Hangi tipte geçerli:** mekanizma `zeus-base`'in içindedir, dolayısıyla `zeus-base`'i alan
   **HER tipte** çalışır — ince WAR tipleri (`zeus-parent`, `zeus-soap-parent`) kadar fat WAR
   tipleri (`zeus-bff-parent`, `zeus-standalone-parent`) için de. Fark yalnız **hangi
   autoconfig'lerin ortada olduğudur**: ince WAR'da paylaşımlı `com.zeus` module'ü tüm
   uygulamaların birleşimini classpath'e koyduğu için veto edilecek çok şey vardır; fat WAR
   tipinde uygulamanın WAR'ında yalnız kendi bağımlılıkları olduğundan çoğu yetenek zaten
   classpath'te yoktur (ör. BFF'te `data-jpa`) ve veto edecek bir şey çıkmaz. **Ama kural
   aynıdır:** fat WAR tipi bir uygulama `zeus-database`'i pom'una koyup `zeus.database.enabled`
   yazmazsa, `ZeusCapabilityVerifier` orada da "Zeus yetenek bildirimi eksik" diyerek açılışı
   durdurur.

   > **TARİHSEL NOT — eski kural (2026-09-11 öncesi).** Bu madde bir dönem, DB kullanmayan
   > uygulamaya şunu eklemesini söylüyordu:
   > ```properties
   > spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,org.springframework.boot.jdbc.autoconfigure.DataSourceInitializationAutoConfiguration
   > ```
   > Bu blok `zeus-sample-soap`'tan **silindi** ve yerini tek satırlık `zeus.soap.enabled=true`
   > bildirimi aldı. Metin, iki çözümün neden birbirinin yerini aldığını anlamak isteyen için
   > tarihsel not olarak bırakıldı — **uygulanacak kural yukarıdakidir**.

3. **BFF route örneği** (context path dahil, doğrulanmış):
   ```properties
   spring.cloud.gateway.server.webmvc.routes[0].id=products-proxy
   spring.cloud.gateway.server.webmvc.routes[0].uri=http://localhost:8080
   spring.cloud.gateway.server.webmvc.routes[0].predicates[0]=Path=/proxy/**
   spring.cloud.gateway.server.webmvc.routes[0].filters[0]=StripPrefix=2
   spring.cloud.gateway.server.webmvc.routes[0].filters[1]=PrefixPath=/spring-wildfly-arch/api
   zeus.bff.spa-fallback.exclude-prefixes=/api/,/proxy/
   ```
4. **SOAP endpoint örneği:** `@WebService @Component("merhabaService")` → otomatik
   `/services/merhabaService` (+ `?wsdl`).

## Doğrulama sonuçları (WildFly 41, Java 25)

| Denetim | Sonuç |
|---|---|
| Standart tip regresyonu: spring-wildfly-arch descriptor'ı ESKİSİYLE bit-bit aynı; deploy + smoke 9/9 | ✅ |
| soap-test: ince WAR yalnız zeus-base+zeus-soap; descriptor'da webservices dışlaması + 2 module | ✅ |
| soap-test canlı: `?wsdl` 200 + SOAP çağrısı yanıtı ("Merhaba, Zeus!") | ✅ |
| com.zeus ∩ com.zeus.soap jar kesişimi BOŞ (küme farkı) | ✅ |
| bff-test: FAT WAR 50 jar (~23 MB), tomcat-embed YOK, descriptor'da dependencies YOK | ✅ |
| bff-test canlı: statik index + SPA fallback + gateway route (gerçek ürün JSON'ı proxy'lendi) | ✅ |
| soap-test kapsam denetimi (com.zeus ∪ com.zeus.soap) | ✅ |

## İlgili Dokümanlar

- `00-Genel-Mimari.md` — parent ailesi özeti.
- `08-wildfly-module-dagitim.md` — module üretimi (`--module soap` eklendi).
- `10-versiyonlu-slot-uretilen-descriptor.md` — slot politikası (com.zeus.soap için de geçerli).
- `13-java25-boot4-wildfly41-yukseltme.md` — bu mimarinin üzerine kurulduğu platform.
