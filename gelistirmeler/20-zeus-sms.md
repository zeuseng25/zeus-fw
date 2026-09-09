# 20 — `zeus-sms`: standart tipte CXF SOAP istemcisi (tasarım)

**Durum:** uygulandı ve doğrulandı (zeus-fw BASE: 79d7122; uçtan uca kanıt: `spring-wildfly-arch`
commit `d9b0742`). Karar tarihi: 2026-09-09. Aşağıdaki "WAR paketleme çelişkisi" bölümünde
tarif edilen paketleme boşluğu **çözüldü** — bkz. "Çözüm: `${zeus.war.packaging-excludes.with-soap}`"
bölümü aşağıda.

Framework'e SMS gönderimi için bir SOAP **istemcisi** ekleniyor. Zorluk şurada: CXF yığını
bugüne kadar yalnız **SOAP tipi** uygulamalara (`zeus-soap-parent` + `com.zeus.soap`) aitti.
`zeus-sms` ise **standart tip** bir uygulamanın (yalnız `com.zeus`) SOAP çağrısı yapmasını
gerektiriyor.

## Karar: CXF yerinde kalır, uygulama opt-in eder

`com.zeus` module'ü **büyümez**. CXF yığını bugünkü yerinde (`com.zeus.soap`, 23 jar) kalır.
SMS kullanan uygulama, standart parent'ta kalarak descriptor'ına opt-in ekler:

```xml
<properties>
    <zeus.descriptor.extra.modules>&lt;module name="com.zeus.soap" slot="${zeus.soap.module.slot}"
        services="import" meta-inf="import" annotations="true"/&gt;</zeus.descriptor.extra.modules>
</properties>
```

`meta-inf="import"` **şarttır**: CXF bus extension'larını `META-INF/cxf` + ServiceLoader ile
bulur; onsuz bus başlamaz.

**Reddedilen alternatif — CXF'i `com.zeus`'a koymak:** SMS'i hiç kullanmayan uygulamalar da
23 jar'ı taşır, `com.zeus` lockstep'i ağırlaşır ve `08-wildfly-module-dagitim.md`'deki
"CXF yığını ayrı `com.zeus.soap` module'üne aittir" kararı geri alınırdı.

**Reddedilen alternatif — konteynerin JBossWS'i:** hiç jar paketlemez ama CXF'e özgü
yeteneklere (interceptor, WS-Security yapılandırması) kod erişimi kalmaz; SMS istemcisinin
correlation ID'yi SOAP header'a basması bunu gerektiriyor.

## Spike: konteynerin CXF'iyle çakışır mı? — ÖLÇÜLDÜ, ÇAKIŞMIYOR

Endişe şuydu: standart tipte `webservices` subsystem'i **aktiftir** (yalnız SOAP şablonu onu
dışlar) ve WildFly kendi CXF'ini taşır (`org/apache/cxf` altında 4 module ağacı). Aynı
deployment iki CXF görürse `LinkageError` beklenir.

Ölçüm: `spring-wildfly-arch` (standart tip) geçici olarak `com.zeus.soap`'ı import edecek
şekilde build edilip WildFly 41'e deploy edildi; deployment'ın gerçekte hangi sınıfları
gördüğü `Class.forName` + `CodeSource` ile yazdırıldı.

| Sınıf | Yüklendiği yer |
|---|---|
| `org.apache.cxf.jaxws.JaxWsProxyFactoryBean` | `modules/com/zeus/soap/main/cxf-rt-frontend-jaxws-4.2.3.jar` |
| `org.apache.cxf.BusFactory` | `modules/com/zeus/soap/main/cxf-core-4.2.3.jar` |
| `jakarta.jws.WebService` | `modules/system/.../jakarta/xml/ws/api/main/jboss-jakarta-xml-ws-api_4.0_spec-1.0.0.Final.jar` |
| `jakarta.xml.ws.Service` | aynı jar |

Deploy'da `ERROR`/`LinkageError`/`ClassCastException` **sıfır**; uygulama
`GET /api/products` → **200**.

**Sonuç:** CXF sınıfları `com.zeus.soap` module classloader'ından geldi, konteynerinkinden
değil. Sebep: WildFly kendi CXF'ini deployment'a yalnız onu **WS deployment'ı** sayınca
ekler — yani `@WebService` taşıyan bir **implementasyon sınıfı** olduğunda. Salt istemci
kullanan düz bir WAR bu tanıma girmez.

**Spike'ın sınırı:** bu ölçüm sınıf görünürlüğünü ve çift kopya olmadığını kanıtlar; tam bir
SOAP gidiş-dönüşünü (bus başlatma, transport, extension yüklenmesi) kanıtlamaz. O,
uygulamada gerçek bir yerel endpoint'e karşı test edilecek.

> **KURAL (sınır koşulu):** `com.zeus.soap`'ı opt-in import eden standart tip uygulamalar
> yalnız **istemci** olabilir. Bir gün `@WebService` implementasyon sınıfı taşırlarsa WildFly
> onları WS deployment sayıp kendi CXF'ini de ekler ve çakışma geri döner — o noktada
> uygulama **SOAP tipine** (`zeus-soap-parent`) geçmelidir.

## Önce kapatılacak boşluk

**`zeus.soap.module.slot` yalnız `zeus-soap-parent`'ta tanımlı** (`zeus-soap-parent/pom.xml:31`).
Standart parent kullanan bir uygulama yukarıdaki opt-in değerini yazarsa property **çözülmez**,
descriptor'a literal `${zeus.soap.module.slot}` girer ve WildFly o adda slot bulamaz.

**Düzeltme:** property `zeus-parent`'a taşınır. Gerekçe: `zeus.module.slot` gibi bir
**platform-release sabiti**dir, tip kararı değil; her tipin görmesi doğrudur. `zeus-soap-parent`
onu miras alır, kendi tanımına gerek kalmaz.

### Yanlış alarm: slot'suz `com.zeus` bağımlılığı (incelendi, kusur DEĞİL)

Kurulu `com.zeus.soap` module.xml'inde `<module name="com.zeus"/>` (slot'suz) görülüyor ve bu
ilk bakışta "versiyonlu slot kullanan bir uygulama iki kopya görür" endişesi doğuruyor.
Kaynağa bakıldığında mekanizma **zaten var**: `install-zeus-module.sh` bir `--base-slot`
argümanı alıyor ve module.xml'i ona göre üretiyor (`scripts/install-zeus-module.sh:181-184`):

```bash
if [[ "${BASE_SLOT}" == "main" ]]; then
    echo '        <module name="com.zeus"/>'
else
    echo "        <module name=\"com.zeus:${BASE_SLOT}\"/>"
fi
```

Slot'suz görünmesinin sebebi varsayılanın `main` olması. Yani tasarımda eksik yok; kalan risk
**operasyoneldir**: `com.zeus` versiyonlu bir slot'a kurulup `com.zeus.soap` `--base-slot`
verilmeden kurulursa, soap module'ü `main`'e bakar. Bu bir kod düzeltmesi değil, runbook
disiplinidir → `17-module-yenileme-runbook.md`'ye adım olarak yazılır.

> Ayrıca doğrulanması gereken bir ayrıntı (bu işin kapsamı dışında): versiyonlu dalda üretilen
> `name="com.zeus:1.1.0"` sözdizimi, klasik `name="com.zeus" slot="1.1.0"` biçiminin yerine
> geçiyor. Bugün her yerde slot `main` olduğu için bu dal hiç çalışmadı; ilk versiyonlu slot
> kurulumunda sınanmalıdır.

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
istemci tarafında aynı desen kullanılır — MDC'deki kimlik giden SOAP header'ına basılır
(`18-correlation-id.md`).

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
  `CorrelationIdPropagationTest` (giden SOAP header'ına correlation ID damgası, MDC boşken
  header eklenmediği negatif yol dahil) ve `ZeusSmsPropertiesTest`. `mvn clean install`
  (2026-09-09 koşusu, `JAVA_HOME=openjdk@25`) **EXIT 0** — tüm modüller dahil tam build yeşil.
- ✅ **Uçtan uca:** standart tipte `spring-wildfly-arch`'a eklenip gerçek WildFly 41'e deploy
  edildi (Task 6, app repo commit `d9b0742`). `POST /api/sms` → beklenen `500`
  (`ZeusSmsException`, karşıda gerçek SMS servisi yok); `ClassNotFoundException`/`LinkageError`/
  `ClassCastException` **yok**. Exception stack trace'i CXF çerçevelerinin
  `com.zeus.soap//org.apache.cxf...` önekiyle basıldığını gösterdi — yani proxy fiilen
  paylaşımlı `com.zeus.soap` module'ünden kuruldu, WAR'ın kendi bundled kopyasından değil.
  Detay ve tam log: `.superpowers/sdd/2026-09-09-zeus-sms/task-6-report.md`.
- ✅ **Regresyon — `com.zeus` module'ünün jar sayısı değişmedi (kararın kanıtı):**
  `ls $WILDFLY_HOME/modules/com/zeus/main/*.jar | wc -l` → **157** (2026-09-09'da yeniden
  ölçüldü, `17-module-yenileme-runbook.md`'deki 2026-08-28 değeriyle aynı). `com.zeus.soap:main`
  → **23** jar (bu doküman başında belirtilen "23 jar" ile tutarlı).
- ✅ **Guard/regresyon script'leri** (2026-09-09 koşusu, hepsi kendi çalışma ağacını
  yeniden kurup ölçüyor):
  - `test-soap-slot-property.sh` → geçti (standart tip, SOAP tipi ve `zeus-parent`'ın kendisi
    hepsi `zeus.soap.module.slot=main`'i çözüyor).
  - `test-com-zeus-cxf-sizintisi.sh` → geçti (`zeus-sms` `zeus-wildfly-module`'ün kapanışında
    değil; `com.zeus:main` kapanışında CXF yok).
  - `test-generate-war-excludes.sh` → geçti (soap dışlama listesi standart listenin üst kümesi,
    193 > 168 artifactId).
  - `test-war-packaging-soap.sh` → geçti (SOAP tipi WAR'da CXF/spring-core yok, yalnız zeus-*).
  - `test-no-war-keep.sh`, `test-coverage-guard.sh`, `test-generator-wiring.sh`,
    `generate-war-excludes.sh --check` → geçti.
  - `test-war-packaging.sh` → geçti (adım A: `spring-wildfly-arch` WAR'ında tam **6** zeus
    jar'ı — `zeus-sms` dahil —, `com.zeus.soap` yığınından **CXF sıfır**; adım B: module'de
    olmayan bağımlılık WAR'a girer). Bu script Task 8'de (aşağıya bkz.) yeni taban sayısına
    ve CXF-sıfır iddiasına güncellendi; artık başarısız değil.

## Çözüm: `${zeus.war.packaging-excludes.with-soap}` — WAR paketleme boşluğu KAPANDI

Yukarıdaki "önce kapatılacak boşluk" ve Task 6'nın uçtan uca doğrulaması, tasarımda ele
alınmamış bir çelişki ortaya çıkarmıştı: `19-war-paketleme-module-farkindaligi.md`'nin denylist
kuralı `zeus-parent`'ın (standart tip) ince-WAR dışlama regex'ini **yalnız
`zeus-wildfly-module`'ün** (`com.zeus`'un) kapanışından üretiyordu; bu doküman ise "CXF
`com.zeus.soap`'ta kalır, uygulama opt-in eder" diyordu — örtük varsayım CXF'in WAR'da hiç
olmamasıydı. Standart tip bir uygulama `com.zeus.soap`'ı opt-in import ettiğinde bu iki kural
birlikte çalışmıyordu: CXF `com.zeus`'ta değil (yalnız `com.zeus.soap`'ta), `zeus-parent`'ın
dışlama listesi `com.zeus.soap`'un varlığından habersizdi, dolayısıyla CXF'in kapanışı
denylist'ten kaçıp WAR'a giriyordu — doküman 19'un kuralına göre **doğru** davranış, ama bu
dokümanın **örtük varsayımına aykırı**. Ölçülen sayı (Task 6/7, düzeltmeden önce): WAR'da
zeus-* olmayan 18 jar (tamamı CXF yığını).

**Karar (Task 8):** üçüncü bir üretilmiş liste — `com.zeus ∪ com.zeus.soap` birleşimi —
`zeus-parent/pom.xml`'e ikinci bir property olarak eklendi: `zeus.war.packaging-excludes.with-soap`
(değeri `zeus-soap-parent`'ın kullandığı `list_soap()` çıktısıyla **birebir aynı**; üretici
`scripts/generate-war-excludes.sh`, bkz. `19-war-paketleme-module-farkindaligi.md`). Opt-in
eden bir standart-tip uygulama **iki satır** yazar:

```xml
<properties>
    <!-- 1) module'ü descriptor'a alır -->
    <zeus.descriptor.extra.modules>&lt;module name="com.zeus.soap" slot="${zeus.soap.module.slot}"
        services="import" meta-inf="import" annotations="true"/&gt;</zeus.descriptor.extra.modules>
    <!-- 2) o module'ün jar'larını WAR'dan da dışlar -->
    <zeus.war.packaging-excludes>${zeus.war.packaging-excludes.with-soap}</zeus.war.packaging-excludes>
</properties>
```

İkinci satır olmadan birinci satır tek başına yeterli **değildir** — CXF hem
`com.zeus.soap` module'ünden gelir hem WAR'da `WEB-INF/lib`'te taşınır: ikinci-kopya
durumu, `08-wildfly-module-dagitim.md`'nin `ClassCastException`/`LinkageError` riski
dediği tam senaryo. **İkisinin birlikte yazılması uygulamanın sorumluluğudur** — bu,
mekanizmanın kabul edilmiş zayıflığıdır; framework bunu POM düzeyinde zorunlu kılamaz
(iki bağımsız property, biri diğerine Maven seviyesinde bağlı değil). Bu insan hatasını
**deploy öncesi yakalanabilir** hâle getirmek için `scripts/verify-module-coverage.sh`'a
üçüncü bir kontrol eklendi (Task 9): descriptor `com.zeus.soap` import ediyorsa ama WAR'ın
`WEB-INF/lib`'inde `cxf-*` jar'ı varsa guard KIRMIZI döner ve çözümü söyler ("
`zeus.war.packaging-excludes`'u `${zeus.war.packaging-excludes.with-soap}`'a yönlendirin").
Kırmızı→yeşil gösterimi ve komut kanıtı: `.superpowers/sdd/2026-09-09-zeus-sms/task-9-report.md`.

**Ölçülen sonuç** (`spring-wildfly-arch`, standart tip, opt-in `com.zeus.soap`, 2026-09-09'da bu
görevde yeniden ölçüldü):

```
$ unzip -l target/*.war | grep -c 'WEB-INF/lib/cxf-'
0
$ unzip -l target/*.war | grep 'WEB-INF/lib/' | awk '{print $4}' | grep -c '^WEB-INF/lib/zeus-'
6
```

CXF **10 → 0** (satır ikisi eklenmeden önceki ara durumda 10 CXF jar'ı vardı — descriptor
opt-in'i zaten yazılmış ama dışlama satırı henüz eklenmemişken; bkz. Task 8 raporu), WAR'da
kalan **tam 6** `zeus-*` jar'ı (`zeus-ai`, `zeus-base`, `zeus-database`, `zeus-logger`,
`zeus-service`, `zeus-sms`). `zeus-soap-parent`'ın davranışı değişmedi (kendi
`MARK_BEGIN/MARK_END` bloğunu kullanır, bu ikinci blok ona dokunmaz).

**Çalışma zamanı garantisi hâlâ gözlemsel:** CXF sınıflarının `com.zeus.soap` module
classloader'ından yüklendiği (WAR'ın kendi bundled kopyasından değil) Task 6'nın uçtan uca
deploy'unda stack-frame önekiyle (`com.zeus.soap//org.apache.cxf...`) doğrulandı; bu, WAR'da
ikinci bir CXF kopyası **olmadığı** için artık zaten geçerli değil (ikinci kopya bu mekanizmayla
engelleniyor) — ama guard'ın kendisi kurulum zamanı bir dosya-sistemi kontrolüdür, WildFly'ın
modül çözümleme sırasına dair bir sözleşme değildir.

## İlgili

- `08-wildfly-module-dagitim.md` — CXF'in neden ayrı module'de olduğu
- `10-versiyonlu-slot-uretilen-descriptor.md` — `zeus.descriptor.extra.modules` mekanizması
- `14-uygulama-tipi-parentlar.md` — tip parent'ları, SOAP hattı
- `18-correlation-id.md` — istemci tarafı damga deseni
- `19-war-paketleme-module-farkindaligi.md` — WAR paketleme denylist'i
