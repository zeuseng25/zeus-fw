# 20 — `zeus-sms`: standart tipte CXF SOAP istemcisi (tasarım)

**Durum:** onaylanmış tasarım, uygulanmadı. Karar tarihi: 2026-09-09.

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
ProblemDetail'e çevirir. Timeout'lar property ile yönetilir — varsayılansız bırakılmaz,
çünkü CXF'in varsayılanı sonsuz beklemedir.

## Test ve doğrulama

- **Birim/entegrasyon:** CXF'in `JaxWsServerFactoryBean`'i ile **yerel gerçek bir endpoint**
  ayağa kaldırılır ve tam SOAP turu atılır (mock değil — serileştirme, bus ve transport
  gerçekten çalışır). Spike'ın kanıtlamadığı kısım budur.
- **Uçtan uca:** standart tipte bir örnek uygulamaya eklenip WildFly'a deploy edilir; SMS
  çağrısının `com.zeus.soap`'tan gelen CXF ile çalıştığı ve deploy'da çakışma olmadığı
  doğrulanır.
- **Regresyon:** `com.zeus` module'ünün jar sayısı **değişmemelidir** (kararın kanıtı).

## İlgili

- `08-wildfly-module-dagitim.md` — CXF'in neden ayrı module'de olduğu
- `10-versiyonlu-slot-uretilen-descriptor.md` — `zeus.descriptor.extra.modules` mekanizması
- `14-uygulama-tipi-parentlar.md` — tip parent'ları, SOAP hattı
- `18-correlation-id.md` — istemci tarafı damga deseni
- `19-war-paketleme-module-farkindaligi.md` — WAR paketleme denylist'i
