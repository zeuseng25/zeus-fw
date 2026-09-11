# 20 — `zeus-sms`: standart tipte CXF SOAP istemcisi (tasarım)

**Durum:** uygulandı ve doğrulandı (zeus-fw BASE: 79d7122; uçtan uca kanıt: `spring-wildfly-arch`
commit `d9b0742`). İlk karar tarihi: 2026-09-09. **2026-09-11'de mekanizma değişti** — bkz. hemen
aşağıdaki "Güncelleme" bölümü; ondan sonraki "Tarihsel karar" bölümü artık **uygulanacak talimat
DEĞİL**, yalnızca kararın neden önce böyle alındığını gösteren kayıttır.

Framework'e SMS gönderimi için bir SOAP **istemcisi** eklendi. Zorluk şuydu: CXF yığını başlangıçta
yalnız **SOAP tipi** uygulamalara (`zeus-soap-parent` + `com.zeus.soap`) aitti; `zeus-sms` ise
**standart tip** bir uygulamanın (yalnız `com.zeus`) SOAP çağrısı yapmasını gerektiriyordu.

## Güncelleme (2026-09-11): opt-in mekanizması kaldırıldı, CXF artık `com.zeus`'ta

CXF yığını `zeus-wildfly-module`'ün (yani `com.zeus`'un) bağımlılık sözleşmesine taşındı; bunu
tetikleyen ayrı bir planın parçasıydı — `.superpowers/sdd/2026-09-11-cxf-com-zeus-ve-script-sertlestirme/`.
Sonuç: aşağıdaki "Tarihsel karar" bölümünün tarif ettiği **her şey silindi**:

- `zeus.descriptor.extra.modules` property'si (opt-in import satırı) — **tamamen kaldırıldı**
  (yalnız SOAP kullanımı değil, mekanizmanın kendisi; doğrulama: `pom.xml`'lerde artık hiç geçmiyor).
- İkinci üretilmiş liste `zeus.war.packaging-excludes.with-soap` — **kaldırıldı**.
- Descriptor şablonundaki extra-module yer tutucusu ve iki-property tutarlılık guard'ı —
  **kaldırıldı**.
- `../spring-wildfly-arch/pom.xml`'deki iki opt-in satırı — **kaldırıldı**.

**Yeni durum:** standart tip bir uygulama, `com.zeus`'a bağlanmaktan (zaten her uygulamanın
yaptığı şey) başka **hiçbir şey yazmadan** CXF'e erişir — ne descriptor'a ek `<module>` satırı, ne
ikinci bir `zeus.war.packaging-excludes` yönlendirmesi. Aşağıdaki "Karar" ve "Çözüm" bölümlerindeki
XML örnekleri artık **uygulanmaz**.

**Ölçülen sonuçlar** (bu görevin kendi koşularından, 2026-09-11):

| Ölçüm | Önce | Sonra |
|---|---|---|
| Standart/SOAP WAR dışlama listesi (artifactId) | 168 | **193** (standart = soap, artık aynı küme) |
| Kurulu `com.zeus:main` module'ü (jar) | 157 | **180** (13'ü CXF) |
| Kurulu `com.zeus.soap:main` module'ü (jar) | 23 | **0** (küme farkıyla boşaldı — bkz. `08-wildfly-module-dagitim.md`) |

Gerçek deploy kanıtı (2026-09-11, `spring-wildfly-arch`, **hiçbir opt-in satırı olmadan**):
`ZeusSmsClient: istemci kuruldu (endpoint=...)` log satırı — yani CXF proxy'si `com.zeus`'un
paylaşılan module'ünden kuruldu, uygulamanın pom'unda tek bir ek satır yokken.

**Sınır kuralı (aşağıdaki "Spike" bölümündeki KURAL) DEĞİŞMEDİ, korunur:** standart tip
uygulamalar CXF'i yalnız **istemci** olarak kullanabilir. `@WebService` **implementasyon** sınıfı
taşıyan bir uygulama hâlâ SOAP tipine (`zeus-soap-parent`) geçmelidir — WildFly onu WS deployment
sayıp kendi CXF'ini ekler, o zaman çakışma geri döner. Bu sınırın nedeni artık "CXF ikinci bir
module'de" değil (o gerekçe ortadan kalktı), ama sonucu aynı: implementasyon `webservices`
subsystem'ini tetikler, istemci tetiklemez (bkz. Spike bölümü, ölçüm hâlâ geçerli).

## Tarihsel karar (2026-09-09, artık geçerli DEĞİL): CXF yerinde kalır, uygulama opt-in eder

> Bu bölüm SADECE kararın ilk hâlinin gerekçesini kaydeder. Aşağıdaki XML **uygulanmaz** —
> güncel mekanizma için yukarıdaki "Güncelleme" bölümüne bakın.

İlk kararda `com.zeus` module'ü **büyümeyecekti**. CXF yığını o zamanki yerinde (`com.zeus.soap`,
23 jar) kalacak, SMS kullanan uygulama standart parent'ta kalarak descriptor'ına opt-in
ekleyecekti:

```xml
<!-- TARİHSEL — artık geçerli değil, uygulamayın -->
<properties>
    <zeus.descriptor.extra.modules>&lt;module name="com.zeus.soap" slot="${zeus.soap.module.slot}"
        services="import" meta-inf="import" annotations="true"/&gt;</zeus.descriptor.extra.modules>
</properties>
```

**O zaman reddedilen alternatif — CXF'i `com.zeus`'a koymak — bu, 2026-09-11'de fiilen YAPILAN
değişikliğin ta kendisidir.** İlk kararın gerekçesi "SMS'i hiç kullanmayan uygulamalar da 23 jar'ı
taşır, `com.zeus` lockstep'i ağırlaşır" idi. Bu maliyet gerçekleşti (module +23 jar büyüdü, 157 →
180) ama ikinci module'ü **her sunucuya kurma zorunluluğunu** ortadan kaldırma kazancı daha ağır
bastı — güncel gerekçe: `08-wildfly-module-dagitim.md` → "Neden CXF artık `com.zeus`'ta".

**O zaman reddedilen alternatif — konteynerin JBossWS'i — hâlâ reddedilir:** CXF'e özgü
yeteneklere (interceptor, WS-Security yapılandırması) kod erişimi kalmaz; SMS istemcisinin
correlation ID'yi giden isteğin protokol header'ına basması bunu gerektiriyor. Bu gerekçe
mekanizma değişikliğinden etkilenmedi.

## Spike: konteynerin CXF'iyle çakışır mı? — ÖLÇÜLDÜ, ÇAKIŞMIYOR (bulgu hâlâ geçerli)

Endişe şuydu: standart tipte `webservices` subsystem'i **aktiftir** (yalnız SOAP şablonu onu
dışlar) ve WildFly kendi CXF'ini taşır (`org/apache/cxf` altında 4 module ağacı). Aynı
deployment iki CXF görürse `LinkageError` beklenir.

Ölçüm (2026-09-09, eski opt-in mekanizmasıyla): `spring-wildfly-arch` (standart tip) geçici
olarak `com.zeus.soap`'ı import edecek şekilde build edilip WildFly 41'e deploy edildi;
deployment'ın gerçekte hangi sınıfları gördüğü `Class.forName` + `CodeSource` ile yazdırıldı.

| Sınıf | Yüklendiği yer (2026-09-09 ölçümü) |
|---|---|
| `org.apache.cxf.jaxws.JaxWsProxyFactoryBean` | `modules/com/zeus/soap/main/cxf-rt-frontend-jaxws-4.2.3.jar` |
| `org.apache.cxf.BusFactory` | `modules/com/zeus/soap/main/cxf-core-4.2.3.jar` |
| `jakarta.jws.WebService` | `modules/system/.../jakarta/xml/ws/api/main/jboss-jakarta-xml-ws-api_4.0_spec-1.0.0.Final.jar` |
| `jakarta.xml.ws.Service` | aynı jar |

Deploy'da `ERROR`/`LinkageError`/`ClassCastException` **sıfır**; uygulama
`GET /api/products` → **200**.

**2026-09-11'den sonraki fark:** CXF artık `com.zeus.soap`'tan değil doğrudan `com.zeus`'tan
gelir (yukarıdaki "Güncelleme" bölümündeki `ZeusSmsClient: istemci kuruldu` log kanıtı bunun
2026-09-11'de gerçek bir deploy'da doğru çalıştığını gösterir). Temel sonuç değişmedi: WildFly
kendi CXF'ini bir deployment'a yalnız onu **WS deployment'ı** sayınca ekler — yani `@WebService`
taşıyan bir **implementasyon sınıfı** olduğunda. Salt istemci kullanan düz bir WAR bu tanıma
girmez.

**Spike'ın sınırı:** bu ölçüm sınıf görünürlüğünü ve çift kopya olmadığını kanıtlar; tam bir
SOAP gidiş-dönüşünü (bus başlatma, transport, extension yüklenmesi) kanıtlamaz. O,
uygulamada gerçek bir yerel endpoint'e karşı test edilmiştir (aşağıdaki "Test ve doğrulama").

> **KURAL (sınır koşulu, DEĞİŞMEDİ):** standart tip uygulamalar CXF'i (artık `com.zeus`
> üzerinden, hiçbir opt-in olmadan erişilse bile) yalnız **istemci** olarak kullanabilir. Bir gün
> `@WebService` implementasyon sınıfı taşırlarsa WildFly onları WS deployment sayıp kendi CXF'ini
> de ekler ve çakışma geri döner — o noktada uygulama **SOAP tipine** (`zeus-soap-parent`)
> geçmelidir.

## Bileşenler

| Sınıf | Görev |
|---|---|
| `ZeusSmsProperties` | `zeus.sms.*` — `endpoint`, `connect-timeout`, `receive-timeout`, `username`, `password` |
| `SmsService` | `@WebService` SEI — `sendSms(String to, String text)` döner `String` (mesaj kimliği) |
| `ZeusSmsClient` | `JaxWsProxyFactoryBean` ile proxy üretir; gönderim, hata sarma ve correlation ID damgası burada |
| `ZeusSmsAutoConfiguration` | `@ConditionalOnProperty("zeus.sms.endpoint")` + `@ConditionalOnMissingBean` |

**SEI elle yazılır, WSDL'den üretilmez.** Gerekçe: repoya bir WSDL koymak ve build'e kod
üretme eklentisi sokmak, örneğin okunabilirliğini ve testini o WSDL'in geçerliliğine bağlar.
Üretimde gerçek SMS servisinin SEI'si aynı desenle yazılır.

**Correlation ID:** `zeus-soap`'ta sunucu tarafı için `CorrelationIdSoapInterceptors` var;
istemci tarafında **aynı mekanizma** kullanılır — `CorrelationId.get()` değeri giden çağrının
**HTTP protokol header'ına** (`CorrelationId.HEADER_NAME` = `X-Correlation-Id`) yazılır
(`18-correlation-id.md`).

Kimlik bilinçli olarak **SOAP zarfına konmaz**. İki gerekçe:

1. **Karşı taraf framework'ün kendisi olabilir.** `zeus-soap`'ın `Inbound` interceptor'ı
   kimliği HTTP header'ından okur. Zarfa `<correlationId>` elemanı konsaydı bir Zeus SOAP
   servisi bu istemciden gelen çağrıda hiçbir kimlik GÖRMEZ, yenisini üretirdi — "çağrı
   zinciri SMS servisinin loglarında da aynı kimlikle izlenebilir" hedefi tam da framework'ün
   kendi sunucularına karşı çalışmazdı (final review, Important 3).
2. **Zarfa dokunmak sözleşmeyi değiştirir.** `CorrelationIdSoapInterceptors`'ın javadoc'undaki
   kural birebir budur: *"SOAP zarfına dokunulmaz, WSDL sözleşmesi değişmez"*. Katı
   `mustUnderstand` denetleyen bir karşı taraf beklenmedik zarf header'ını reddedebilir.

MDC anahtarı da elle yazılmaz: `CorrelationId.get()` / `CorrelationId.HEADER_NAME` sabitleri
repodaki tek kaynaktır.

## Hata yönetimi

`zeus.sms.endpoint` tanımsızsa auto-config **hiç devreye girmez**. Bu, "module geniştir,
uygulama dardır" kuralının gereği: `zeus-sms` jar'ını gören ama SMS kullanmayan bir uygulama
bean kurmaz, endpoint aramaz, hata vermez.

Çağrı hataları `ZeusSmsException`'a sarılır; `zeus-base`'in `GlobalExceptionHandler`'ı
ProblemDetail'e çevirir. Timeout'lar property ile yönetilir — varsayılansız bırakılmaz:
CXF'in varsayılanları 30s bağlantı / 60s yanıttır ve bir istek thread'ini bir dakika
tutabilecek bu değerler bir SMS çağrısı için fazlasıyla uzundur.

## Test ve doğrulama

- ✅ **Birim/entegrasyon:** `zeus-sms/src/test/java/com/zeus/framework/sms/ZeusSmsClientTest.java`
  CXF'in `JaxWsServerFactoryBean`'i ile **yerel gerçek bir endpoint** ayağa kaldırıp tam SOAP
  turu atıyor (mock değil — serileştirme, bus ve transport gerçekten çalışıyor); ayrıca
  `CorrelationIdPropagationTest` (giden çağrının `X-Correlation-Id` **protokol header'ına**
  correlation ID damgası — sunucu tarafında `zeus-soap`'ın `Inbound`'u ile AYNI yoldan
  okunarak; kimlik yokken header eklenmediği negatif yol dahil) ve `ZeusSmsPropertiesTest`. `mvn clean install`
  (2026-09-09 koşusu, `JAVA_HOME=openjdk@25`) **EXIT 0** — tüm modüller dahil tam build yeşil.
- ✅ **Uçtan uca (2026-09-09, ESKİ opt-in mekanizmasıyla):** standart tipte `spring-wildfly-arch`'a
  eklenip gerçek WildFly 41'e deploy edildi (Task 6, app repo commit `d9b0742`). `POST /api/sms` →
  beklenen `500` (`ZeusSmsException`, karşıda gerçek SMS servisi yok); `ClassNotFoundException`/
  `LinkageError`/`ClassCastException` **yok**. Exception stack trace'i CXF çerçevelerinin
  `com.zeus.soap//org.apache.cxf...` önekiyle basıldığını gösterdi — yani proxy fiilen
  paylaşımlı `com.zeus.soap` module'ünden kuruldu, WAR'ın kendi bundled kopyasından değil.
  Detay ve tam log: `.superpowers/sdd/2026-09-09-zeus-sms/task-6-report.md`.
- ✅ **Uçtan uca (2026-09-11, YENİ mekanizma — CXF `com.zeus`'ta, hiçbir opt-in satırı olmadan):**
  `spring-wildfly-arch` gerçek WildFly 41'e deploy oldu, log'da
  `ZeusSmsClient: istemci kuruldu (endpoint=...)`; hata taramasında `ERROR`/`LinkageError`/
  `ClassCastException`/`NoClassDefFoundError`/`OutOfMemory` **sıfır**. Aynı koşuda SOAP tipi
  `zeus-sample-soap` da deploy oldu (`?wsdl` → 200, SOAP POST → 200) — bu, `com.zeus.soap`'ın
  jar'sız kalmasının (küme farkı ∅) deploy'u bozmadığının kanıtıdır. Tam log ve komutlar:
  `.superpowers/sdd/2026-09-11-cxf-com-zeus-ve-script-sertlestirme/task-3-report.md`.
- ✅ **Regresyon — `com.zeus` module'ünün jar sayısı (kararın tarihçesi):**
  2026-09-09'da (opt-in dönemi) **157** idi — o dönem CXF `com.zeus`'a hiç girmemişti, karar
  buydu. 2026-09-11'de (CXF taşındıktan sonra) **180**'e çıktı (13'ü CXF) — bu bir regresyon
  DEĞİL, "Güncelleme" bölümündeki bilinçli kararın doğrudan sonucu. `com.zeus.soap:main`
  2026-09-09'da 23 jar iken 2026-09-11'de küme farkıyla **0**'a düştü (aynı gerekçe).
- ✅ **Guard/regresyon script'leri** (2026-09-11 koşusu, `./scripts/run-guards.sh` — 11/11 yeşil):
  - `test-soap-slot-property.sh` → geçti (`zeus.soap.module.slot` hâlâ tanımlı ve her tipte
    `main`'i çözüyor; bu property SOAP tipinin **kendi** descriptor'ı için hâlâ kullanılıyor,
    opt-in mekanizmasından bağımsız olarak kaldı).
  - `test-com-zeus-cxf-sozlesmesi.sh` → geçti. **İsim ve anlam değişti:** eskiden
    `test-com-zeus-cxf-sizintisi.sh` idi ve "`com.zeus` kapanışında CXF YOK" iddia ediyordu
    (o zamanki doğru davranış). Bugün TERSİNİ, yani "`com.zeus` kapanışında CXF **VAR**"
    iddia ediyor — isim de bunu yansıtacak şekilde `-sozlesmesi` (sözleşme) olarak değiştirildi.
  - `test-com-zeus-jakarta-api-kapsama.sh` → geçti (yeni guard, CXF'in gerektirdiği
    `jakarta.xml.ws.api`/`jakarta.xml.soap.api`'nin module.xml'in TEMEL dalında export
    edildiğini doğrular — CXF `com.zeus`'a taşındığı için gerekli oldu).
  - `test-module-liste-esitligi.sh` → geçti (kurulu module ↔ üretilen liste iki yönlü eşitliği;
    **180 module jar'ı ∧ 172 dışlanan artifactId**, standart ve soap çiftlerinin ikisinde de).
  - `test-generate-war-excludes.sh`, `test-no-war-keep.sh`, `test-generator-wiring.sh`,
    `test-coverage-guard.sh`, `verify-module-coverage.sh`, `./scripts/generate-war-excludes.sh
    --check` → hepsi geçti.
  - `test-war-packaging.sh` → geçti (standart tip WAR'ında **6** zeus jar'ı — `zeus-sms`
    dahil —, WAR'da CXF **sıfır**: CXF artık WAR'a değil `com.zeus`'a gider).
  - `test-war-packaging-soap.sh` → geçti (SOAP tipi WAR'ında da CXF **sıfır**, yalnız `zeus-*`).
  - **Kaldırılan guard'lar** (mekanizma silindiği için artık yok): eski iki-property tutarlılık
    guard'ı (`zeus.descriptor.extra.modules` ↔ `zeus.war.packaging-excludes.with-soap`
    eşleşmesini denetleyen test) — property'lerin ikisi de silindiği için denetlenecek bir şey
    kalmadı.

## Tarihsel: WAR paketleme çelişkisi ve `${zeus.war.packaging-excludes.with-soap}` çözümü (artık geçersiz)

> Bu bölüm SADECE tarihsel kayıt içindir — 2026-09-09'da ortaya çıkan ve o gün
> `zeus.war.packaging-excludes.with-soap` ile "çözülen" bir çelişkiyi anlatır. 2026-09-11'de
> CXF `com.zeus`'a taşınınca **çelişkinin kendisi ortadan kalktı**: standart tip bir uygulama
> artık `com.zeus.soap`'ı hiç opt-in import etmiyor (mekanizma kalktı), dolayısıyla "ikinci
> bir dışlama listesine yönlendirme" ihtiyacı da kalmadı. Aşağıdaki XML uygulanmaz.

O gün, "önce kapatılacak boşluk" ve Task 6'nın uçtan uca doğrulaması bir çelişki ortaya
çıkarmıştı: `19-war-paketleme-module-farkindaligi.md`'nin denylist kuralı `zeus-parent`'ın
ince-WAR dışlama regex'ini **yalnız `zeus-wildfly-module`'ün** (`com.zeus`'un) kapanışından
üretiyordu; standart tip bir uygulama `com.zeus.soap`'ı opt-in import ettiğinde CXF'in kapanışı
bu listeden kaçıp WAR'a giriyordu. Çözüm olarak üçüncü bir üretilmiş liste
(`zeus.war.packaging-excludes.with-soap`, `com.zeus ∪ com.zeus.soap` birleşimi) eklenmiş ve
opt-in eden uygulamalardan **iki satır** yazması istenmişti (descriptor import + dışlama listesi
yönlendirmesi) — ikinci satır unutulursa çift kopya / `LinkageError` riski doğardı.

2026-09-11'de bu tasarımın **kendisi** kaldırıldı: `com.zeus`'un dışlama listesi zaten CXF'i
kapsıyor (CXF artık `com.zeus`'un kapanışında), `com.zeus.soap` opt-in'i hiç yapılmadığından
ikinci listeye de gerek kalmadı. Detay ve ölçümler: `19-war-paketleme-module-farkindaligi.md`.

## İlgili

- `08-wildfly-module-dagitim.md` — CXF'in neden artık `com.zeus`'ta olduğu (önceki "ayrı
  module'e ait" kararının neden tersine döndüğü) + `com.zeus.soap`'ın boş module olmasının
  ve `empty-index`'in gerekçesi
- `14-uygulama-tipi-parentlar.md` — tip parent'ları, SOAP hattı
- `18-correlation-id.md` — istemci tarafı damga deseni
- `19-war-paketleme-module-farkindaligi.md` — tek üretilmiş WAR dışlama listesi, iki yönlü guard
