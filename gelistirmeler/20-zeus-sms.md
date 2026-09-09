# 20 — `zeus-sms`: standart tipte CXF SOAP istemcisi (tasarım)

**Durum:** uygulandı ve doğrulandı (zeus-fw BASE: 79d7122; uçtan uca kanıt: `spring-wildfly-arch`
commit `d9b0742`). Karar tarihi: 2026-09-09. **Bir açık iş var** — bkz. "Kritik bulgu: WAR
paketleme çelişkisi" bölümü aşağıda.

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
  - ❌ **`test-war-packaging.sh` BAŞARISIZ** (adım A, "yalnız 5 zeus jar'ı" iddiası). Sebep
    doğrulanamama değil — kesin: bu script `spring-wildfly-arch`'ı sabit bir regresyon
    referansı (5 zeus jar'ı, CXF yok) olarak kabul ediyor, ama Task 6'nın uçtan uca kanıtı için
    kullandığı app-repo commit'i (`d9b0742`) o uygulamaya kalıcı olarak `com.zeus.soap` opt-in
    SOAP istemcisini ekledi. Şu an `spring-wildfly-arch`'ın WAR'ı 6 zeus jar'ı (+ `zeus-sms`)
    ve 18 CXF-yığını jar'ı taşıyor — script'in beklediği "değişmeyen 5 jar'lık temel" artık
    doğru değil. Bu, script'in bir kusuru değil; script'in referans aldığı app-repo durumunun
    bu SDD işiyle bilerek değiştirilmiş olmasının doğal sonucu ve aşağıdaki "Kritik bulgu"
    ile aynı kök nedeni paylaşıyor. **Açık iş** — bkz. aşağıda.

## Kritik bulgu: WAR paketleme çelişkisi (opt-in senaryosu) — AÇIK İŞ

Task 6'nın uçtan uca doğrulaması, tasarımda ele alınmamış bir çelişki ortaya çıkardı.

**İki dokümanın söylediği çelişiyor:**
- `19-war-paketleme-module-farkindaligi.md` (denylist kuralı): "`com.zeus` module'ünün
  kapanışında OLMAYAN her runtime bağımlılık WAR'da taşınır." `zeus-parent`'ın (standart tip)
  ince-WAR dışlama regex'i **yalnız `zeus-wildfly-module`'ün** (yani `com.zeus`'un) kapanışından
  üretiliyor.
- Bu doküman (20): "CXF `com.zeus.soap`'ta kalır, uygulama opt-in eder" — örtük varsayım CXF'in
  WAR'da OLMAMASI.

Standart tip bir uygulama `com.zeus.soap`'ı opt-in import ettiğinde (`zeus-sms` kullanan her
uygulamanın yapması gereken tam senaryo) bu iki kural birlikte çalışmıyor: CXF `com.zeus`'ta
değil (yalnız `com.zeus.soap`'ta), `zeus-parent`'ın dışlama listesi `com.zeus.soap`'un
varlığından habersiz, dolayısıyla CXF'in `compile` scope'lu bağımlılıkları (transitif
kapanışıyla) denylist'ten kaçıp WAR'a giriyor — doküman 19'un kuralına göre **doğru** davranış,
ama doküman 20'nin **örtük varsayımına aykırı**.

**Ölçülen sayı:** `spring-wildfly-arch`'ın (standart tip, opt-in `com.zeus.soap`) WAR'ında
`WEB-INF/lib/` altında zeus-* olmayan **18 jar** var — tamamı CXF yığını
(`cxf-core`, 9 × `cxf-rt-*`, `neethi`, `stax2-api`, `woodstox-core`, `wsdl4j`, `xml-resolver`,
`xmlschema-core`, `asm`, `angus-mail`). Doğrulama komutu (2026-09-09):

```
$ unzip -l spring-wildfly-arch/target/*.war | grep 'WEB-INF/lib/' | awk '{print $4}' \
    | grep -v '^WEB-INF/lib/zeus-' | wc -l
18
```

(Task 6 raporundaki "11 jar" ifadesi yanlıştır — CXF'in tam transitif kapanışı 11 değil 18
jar'dır; yukarıdaki komut kesin sayıdır.)

**Çalışma zamanı durumu — dürüst ifade:** bu koşuda gözlemsel olarak çakışma olmadı (CXF
sınıfları module'den yüklendi, stack frame'lerdeki `com.zeus.soap//org.apache.cxf...` öneki ile
doğrulandı — Task 6 raporu, "Kritik doğrulama" bölümü) — **ancak bu bir sözleşme değildir**.
`08-wildfly-module-dagitim.md`'nin ikinci-kopya kuralı (ojdbc bölümü) tam olarak bunu söylüyor:
module'deki kopyanın yanında WAR'da ikinci bir kopya bulunması `ClassCastException` /
`LinkageError` riski taşır; bu koşuda WildFly'ın modül bağımlılık çözümleme SIRASI (import
edilen module, yerel `WEB-INF/lib`'e göre öncelikli) lehimize çalıştı, ama bu WildFly/JBoss
Modules'ın belgelenmiş bir sözleşmesi değil, tek bir başarılı deploy'da gözlemlenen bir sonuç.
Aynı ikinci-kopya kuralı burada da geçerlidir ve tek bir başarılı deploy bunun garantisi
değildir.

**Kapsam dışı bırakılma gerekçesi:** çözüm `zeus-parent/pom.xml` ve/veya
`scripts/generate-war-excludes.sh` üzerinde ayrı bir framework tasarım kararı gerektiriyor
(ör. "standart + SOAP-opt-in" için üçüncü bir dışlama listesi mi, yoksa uygulamanın kendi
`zeus.war.packaging-excludes`'ı override etmesi mi beklenir?). Task 6 ve bu görev (Task 7)
ikisi de brief kapsamlarının dışında bağımsız bir framework tasarım kararı almayı
yetkilendirmiyor ("sen subagent dağıtmazsın" sözleşmesi altında böyle bir kararı tek başına
almak uygun değil).

**AÇIK İŞ:** standart tip + `com.zeus.soap` opt-in kombinasyonu için WAR dışlama listesi
kararı — ayrı bir SDD görevi olarak ele alınmalı. Bu kombinasyonu kullanan her gelecekteki
uygulama (yalnız `zeus-sms` değil, ileride `com.zeus.soap`'ı opt-in eden başka her modül) aynı
şişkinliği ve aynı belgelenmemiş çakışma riskini miras alır.

## İlgili

- `08-wildfly-module-dagitim.md` — CXF'in neden ayrı module'de olduğu
- `10-versiyonlu-slot-uretilen-descriptor.md` — `zeus.descriptor.extra.modules` mekanizması
- `14-uygulama-tipi-parentlar.md` — tip parent'ları, SOAP hattı
- `18-correlation-id.md` — istemci tarafı damga deseni
- `19-war-paketleme-module-farkindaligi.md` — WAR paketleme denylist'i
