# Yetenek opt-in'i: paylaşımlı module'ün autoconfig yan etkisini kesmek

**Tarih:** 2026-09-11 · **Durum:** tasarım, onay bekliyor

## Problem

`com.zeus` module'ü **tüm uygulamaların bağımlılık birleşimidir** — tasarım gereği. Bir uygulama
framework'ü kullanarak AI geliştirmek isterken diğeri istemez, ama module tektir. Sonuç: AI
kullanmayan bir uygulamanın classpath'inde de `spring-ai-autoconfigure-*` durur ve Spring Boot
onları yapılandırmaya çalışır.

Bugünkü hâli — `zeus-sample-soap/src/main/resources/application.properties`:

```properties
spring.autoconfigure.exclude=\
  org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,\
  org.springframework.boot.jdbc.autoconfigure.DataSourceInitializationAutoConfiguration,\
  org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration,\
  org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration

spring.ai.openai.api-key=kullanilmiyor
```

AI kullanmayan bir uygulama **sahte bir API anahtarı** yazmak zorunda. Bu bir workaround değil,
teşhis: yanlış tarafta bir varsayılan var.

**Asıl maliyet ölçeklenmede.** Module'e yeni bir yetenek girdiğinde N uygulamanın *hepsinin*
`application.properties`'i güncellenmeli, yoksa deploy'da düşerler. Framework'ün büyümesi
tüketicilerin bakım yükü hâline gelir — paylaşımlı module'ün varlık sebebinin tam tersi.

Ölçülen durum (2026-09-11, kurulu `com.zeus:main`): **21 jar** `AutoConfiguration.imports`
taşıyor, toplam **~90 autoconfig sınıfı**, ve hepsi her uygulamada çalışıyor.

## Kararlar (kullanıcı, 2026-09-11)

1. **Varsayılan KAPALI.** Uygulama bir yeteneği açıkça ister. Gerekçe: module büyüdüğünde mevcut
   uygulamalar etkilenmemeli.
2. **API: yetenek başına anahtar** — `zeus.<yetenek>.enabled=true`. Zeus'un mevcut property
   deseniyle aynı; tek anahtar hem zeus'un kendi autoconfig'ini hem 3. partileri yönetir.
3. **Sert geçiş.** Ara sürüm yok. Deklarasyon unutulursa açılışta konuşan bir hata verilir —
   uygulama önce dev'e deploy olacağı için orada yakalanır.
4. **Module küçültülmez.** Tek `com.zeus`'un varlık sebebi paylaşım; AI'yı çıkarmak AI'lı
   uygulamayı 15 jar'ı WAR'ında taşımaya iter ve o yetenek için paylaşımı çökertir.
5. **Swagger/springdoc her uygulamada varsayılan açık kalır** — yetenek değildir.

## Mimari

Üç parça, hepsi `zeus-base`'de (her uygulamanın zaten bağlandığı modül).

### 1. Yetenek kaydı — `ZeusCapabilities`

Her yetenek üç şeyden oluşur: **ad**, **işaretçi sınıf**, **sahiplendiği 3. parti autoconfig
paket önekleri**.

| Yetenek | Anahtar | İşaretçi sınıf | Sahiplendiği autoconfig önekleri |
|---|---|---|---|
| ai | `zeus.ai.enabled` | `com.zeus.framework.ai.ZeusAiAssistant` | `org.springframework.ai.` |
| database | `zeus.database.enabled` | `com.zeus.framework.database.StoredProcedureExecutor` | `org.springframework.boot.jdbc.autoconfigure.`, `org.springframework.boot.hibernate.autoconfigure.`, `org.springframework.boot.data.jpa.autoconfigure.`, `org.springframework.boot.persistence.autoconfigure.` |
| soap | `zeus.soap.enabled` | `com.zeus.framework.soap.ZeusSoapEndpointRegistrar` | `org.apache.cxf.spring.boot.autoconfigure.` |

**Her zaman serbest** (hiçbir yeteneğe ait değil, veto edilmez): `spring-boot-autoconfigure`
çekirdeği, `webmvc`, `servlet`, `jackson`, `validation`, `http-client`/`http-codec`/
`http-converter`, `restclient`, `webclient`, `reactor`, `data-commons`, `springdoc`.

İsimlendirme kuralı: yetenek anahtarı **modül adını** izler (`zeus-database` →
`zeus.database.enabled`). `zeus.correlation.datasource.enabled` gibi mevcut alt anahtarlar
yetenek *içi* ince ayar olarak kalır; yeni bir kavram doğmaz.

### 2. Filtre — `ZeusAutoConfigurationFilter`

`AutoConfigurationImportFilter` + `EnvironmentAware`, `zeus-base`'in
`META-INF/spring.factories`'inde kayıtlı.

Bu, Spring'in **resmî uzantı noktasıdır** — Spring Boot'un kendi `OnClassCondition`,
`OnBeanCondition`, `OnWebApplicationCondition`'ı aynı kancayla kayıtlıdır (doğrulandı:
`spring-boot-autoconfigure-4.0.7.jar!/META-INF/spring.factories`). Context kurulmadan önce
çalışır; maliyeti ad karşılaştırmasıdır.

Kural, aday sınıf adı başına:

```
sınıf bir yeteneğin önekiyle başlıyorsa:
    zeus.<yetenek>.enabled == true  →  geçir
    aksi hâlde                      →  VETO
aksi hâlde (sahipsiz)               →  geçir
```

`zeus.ai.enabled`'ın `matchIfMissing`'i `true` → `false` olur
(`ZeusAiAutoConfiguration:31`), böylece tek anahtar iki tarafı da yönetir.

### 3. Çelişki denetimi — `ZeusCapabilityVerifier`

`EnvironmentPostProcessor` (yine `spring.factories`). İşaretçi sınıf classpath'te **ama**
property yok → açılışta hata:

```
HATA: 'zeus-ai' bağımlılığı bu uygulamanın WAR'ında var ama 'zeus.ai.enabled' yazılmamış.
      AI yeteneği KAPALI kalır ve ZeusAiAssistant bean'i kurulmaz.
      Kullanacaksanız application.properties'e ekleyin:  zeus.ai.enabled=true
      Kullanmayacaksanız pom.xml'den zeus-ai bağımlılığını kaldırın.
```

**Bu opt-out'a geri dönmek değildir.** Bildirilmemiş bir yetenek meşru bir durumdur (REST-only
uygulama hiçbir şey yazmaz ve hata almaz). Hata yalnız bir **çelişkide** çıkar: uygulama
bağımlılığı pom'una yazmış ama açmamış.

İşaretçi sınıf neden güvenilir bir sinyal: `install-zeus-module.sh:98` `EXCLUDE_REGEX`'i
`zeus-[a-z0-9-]+` içerir, yani **zeus jar'ları module'e girmez, WAR'da taşınır**. Dolayısıyla
`WEB-INF/lib/zeus-ai.jar`'ın varlığı = "bu uygulama AI'yı pom'una yazdı".

### Kaçış kapıları

| Property | Etki |
|---|---|
| `zeus.autoconfig.filter.enabled=false` | Filtreyi tamamen kapatır (bugünkü davranış) |
| `zeus.autoconfig.force-include=<sınıf,sınıf>` | Adı verilen autoconfig'i veto etme |

Bunlar olmadan framework'ün bir sınıflandırma hatası uygulamayı tamamen kilitler. Kaçış kapısı
kullanan uygulama bunu teknik borç olarak izler.

## Hata yönetimi: çalışma zamanı açık, build kapalı

Bilinçli asimetri:

- **Çalışma zamanı fail-open.** Filtre tanımadığı sınıfı veto **etmez**. Framework'ün eksik bir
  sınıflandırması bir uygulamayı kırmamalı.
- **Build fail-closed.** Sınıflandırılmamış bir autoconfig build'i **kırar** (aşağıdaki guard).

Böylece yeni bir yetenek module'e girdiğinde sessizce her uygulamada çalışmaya başlamaz — ama
kaçan bir tane de üretimi düşürmez.

## Test

**Birim (`zeus-base`):**
- Yetenek kapalı → o yeteneğin sınıfları veto edilir; açık → geçer.
- Sahipsiz sınıf her hâlükârda geçer.
- `force-include` vetoyu ezer; `filter.enabled=false` tüm mekanizmayı kapatır.
- Verifier: işaretçi var + property yok → hata; işaretçi yok + property yok → sessiz;
  işaretçi var + property var → sessiz.

**Guard — `scripts/test-autoconfig-sahipligi.sh`** (`run-guards.sh`'a kaydedilir):
Kurulu module'deki her jar'ın `AutoConfiguration.imports` girdilerini çıkarır; her sınıf ya bir
yeteneğin önekine ya da "her zaman serbest" listesine düşmeli. Düşmeyen varsa **KIRMIZI**.
Sınıflandırma listesi `ZeusCapabilities`'ten okunur, script'e kopyalanmaz (repodaki
`test-com-zeus-jakarta-api-kapsama.sh` deseninin aynısı: türet, sabitleme).
`mvn`/`unzip` başarısızlığında ve boş çıktıda **hard failure**.

**Entegrasyon (gerçek deploy):**
- `zeus-sample-soap`: `application.properties`'teki 4 exclude + sahte `api-key` **silinir**,
  uygulama yine açılır. Bu, işin bittiğinin kanıtıdır.
- `spring-wildfly-arch`: `zeus.database.enabled=true` ekler, `/api/products` 200 döner.
- AI'lı bir uygulama (`zeus.ai.enabled=true`) ile AI'sız bir uygulama **aynı sunucuda, aynı
  module'le** yan yana çalışır — problemin ta kendisi olan senaryo.

## Kapsam dışı (bilinçli)

- Module'ü küçültmek (karar 4).
- `springdoc`'u yetenek yapmak (karar 5).
- `zeus-redis` / `zeus-batch`: iskelet durumdalar ve zaten module'de değiller; yetenek kaydına
  gerçek hâle geldiklerinde eklenirler.
- Mevcut `zeus.correlation.*` / `zeus.sms.*` anahtarlarının yeniden adlandırılması.

## Riskler

1. **Sınıflandırma hatası** bir uygulamanın ihtiyacı olan autoconfig'i veto edebilir. Azaltma:
   fail-open + `force-include` + guard'ın yeni gireni yakalaması.
2. **Sert geçiş** mevcut uygulamaları kırar. Kabul edilen risk (karar 3); ekosistem küçük
   (`spring-wildfly-arch` + 3 sample + `test-project--service`) ve hata mesajı düzeltmeyi
   birebir söylüyor.
3. **`test-project--service`** bu framework sürümüyle hâlâ `could not load JDBC driver class`
   alıyor (ayrı, ertelenmiş konu — `gelistirmeler/10-*.md`). Bu değişiklik onu düzeltmez ve
   kötüleştirmez; o uygulama `zeus.database.enabled=true` de yazmak zorunda kalacaktır.

4. **SOAP İSTEMCİSİ ile SOAP SUNUCUSU aynı anahtara bağlanmamalı — uygulamada doğrulanacak.**
   `zeus.soap.enabled` CXF autoconfig'lerini yönetiyor, ama `zeus-sms` CXF'i **istemci** olarak
   kullanıyor ve `zeus-soap`'a bağlı değil (`ZeusSmsClient`, `JaxWsProxyFactoryBean`'i doğrudan
   kurar). Beklenti: istemcinin Spring tarafından yönetilen bir `Bus` bean'ine ihtiyacı yok,
   varsayılan bus yeter — yani CXF autoconfig'i veto edilse bile SMS çalışır. **Bu bir
   varsayımdır ve uygulamanın ilk adımında ölçülmelidir**: `zeus.soap.enabled` yazmayan bir
   uygulamada `ZeusSmsClient` gerçek bir SOAP çağrısı yapabiliyor mu? Yapamıyorsa istemci
   tarafı ayrı bir anahtara (`zeus.sms.*` zaten var) veya "her zaman serbest" listesine alınır.
   Bu ölçüm yapılmadan yetenek kaydı kesinleşmiş sayılmaz.
