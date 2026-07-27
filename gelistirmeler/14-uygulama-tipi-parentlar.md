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

Kalıtım zinciri: `zeus-soap-parent` ve `zeus-bff-parent` → `zeus-parent` → `zeus-fw` →
`spring-boot-starter-parent`. BOM import'u, lombok, repackage-skip, flatten ve üretilen
descriptor mekanizması miras alınır; yalnız farklar override edilir.

## Mekanizma: property ile tip seçimi

`zeus-parent` iki property'yi genelleştirir; tip parent'ları yalnız bunları override eder:

| Property | zeus-parent (standart) | zeus-soap-parent | zeus-bff-parent |
|---|---|---|---|
| `zeus.descriptor.dir` | `descriptor-standard` | `descriptor-soap` | `descriptor-bff` |
| `zeus.war.packaging-excludes` | ince WAR regex'i | (miras — ince WAR) | **BOŞ** (fat WAR) |

Descriptor şablonları `zeus-war-defaults/src/main/resources/` altında tip başına ayrı
dizindedir; `zeus-generated-descriptor` profili `${zeus.descriptor.dir}`'i unpack edip
WEB-INF'e filtreleyerek koyar. (XML fragment'i property'ye gömme yaklaşımı bilinçli
REDDEDİLDİ — okunmaz/kırılgandır; ayrı dosyalar diff'lenebilir.)

- **descriptor-soap** farkları: `<subsystem name="webservices"/>` dışlaması (WildFly'ın kendi
  JBossWS/CXF'i deployment'a karışmasın — sınıf çakışması önlenir) + `com.zeus.soap` module
  bağımlılığı (`${zeus.soap.module.slot}` ile, varsayılan main).
- **descriptor-bff** farkları: `<dependencies>` bloğu YOK (com.zeus kullanılmaz); subsystem
  dışlamaları standartla aynı.

## SOAP hattı (Apache CXF 4.2.2)

- **Neden CXF 4.2.x:** Jakarta EE 11 + Spring Framework 7 + Boot 4 uyumlu ilk satır.
  Sürüm BOM'da: `cxf.version` + `cxf-spring-boot-starter-jaxws` yönetimi.
- **`zeus-soap` modülü:** CXF starter'ın kurduğu Bus/CXFServlet üstüne
  `ZeusSoapEndpointRegistrar` — `@WebService` işaretli bean'leri `/services/<beanAdı>`
  altında otomatik yayınlar. Uygulama yalnızca `@WebService @Component` sınıf yazar.
- **`com.zeus.soap` WildFly module'ü:** sözleşmesi `zeus-soap-wildfly-module`; üretimi
  `./scripts/install-zeus-module.sh --module soap [--slot X] [--base-slot Y]`.
  Jar seti = CXF kapanışı **EKSİ** com.zeus kapanışı (**küme farkı** — script temel kapanışı
  da çözüp aynı ada sahip jar'ları atlar; çift jar/LinkageError imkânsızlaşır. Provided
  hilesi kullanılmadı: CXF, Spring jar'larını kendi compile yolundan da çektiği için
  nearest-wins belirsiz olurdu). 23 jar: cxf-core/rt-*, wsdl4j, woodstox, xmlschema, neethi...
  module.xml, `com.zeus`'a (base-slot) ve `jakarta.xml.ws/soap/bind/...` server API'lerine bağımlıdır.
- **Kapsam denetimi:** `verify-module-coverage.sh`, uygulamada `zeus.soap.module.slot`
  property'sini görürse denetimi **com.zeus ∪ com.zeus.soap birleşimine** karşı yapar.

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
2. **DB kullanmayan uygulama** (ör. çoğu SOAP/BFF): paylaşımlı module `starter-data-jpa`
   taşıdığı için Boot JPA autoconfig'i tetiklenir; datasource'suz uygulama şunu ekler:
   ```properties
   spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,org.springframework.boot.jdbc.autoconfigure.DataSourceInitializationAutoConfiguration
   ```
   (BFF fat WAR'ında data-jpa zaten yoktur — bu kural ince WAR tipleri içindir.)
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
