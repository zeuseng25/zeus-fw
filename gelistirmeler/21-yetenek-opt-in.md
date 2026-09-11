# 21 — Yetenek Opt-in'i (paylaşımlı module'ün autoconfig yan etkisi)

Bu doküman, `com.zeus` module'ünün **birleşim** (union) olmasının Spring Boot autoconfig'e
verdiği yan etkiyi ve bunu çözen **opt-in** mekanizmasını tanımlar: kayıt (`ZeusCapabilities`),
filtre (`ZeusAutoConfigurationFilter`) ve çelişki denetleyicisi (`ZeusCapabilityVerifier`).

## Problem — module geniştir, uygulama dardır

`08-wildfly-module-dagitim.md`'de anlatıldığı gibi, `com.zeus` **tüm uygulamaların runtime
bağımlılıklarının birleşimidir** ve bilinçli olarak **daraltılmaz** — bir uygulama AI
kullanmasa da, AI kullanan başka bir uygulama olduğu sürece `spring-ai-*` jar'ları module'de
kalır ve her uygulamanın classpath'inde görünür. Spring Boot bunları **otomatik yapılandırmaya
çalışır**: veritabanı kullanmayan bir uygulama `Failed to determine a suitable driver class`,
AI kullanmayan bir uygulama `At least one credential source must be specified` hatasıyla
deploy'da düşer.

Eski çözüm uygulamanın kendisindeydi — her uygulama kendi `application.properties`'ine
`spring.autoconfigure.exclude` ile hangi autoconfig'leri istemediğini elle yazardı (bkz. doküman
08'in ilgili bölümündeki **tarihsel not**). Bu, üç sorunu vardı: (1) her uygulama Spring Boot'un
iç paket adlarını (`org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration`
gibi) ezbere bilmek zorundaydı, (2) `zeus-sample-soap` gibi hem veritabanı hem AI hem de gerçek
olmayan bir OpenAI anahtarı istemeyen bir uygulama **14 satırlık** bir workaround bloğuna ve
sahte bir `spring.ai.openai.api-key=kullanilmiyor` satırına mahkumdu, (3) daraltma her
uygulamada **ayrı ayrı, tutarsız** bir şekilde tekrarlanıyordu.

## Karar — opt-in, framework seviyesinde

Daraltma artık **uygulamanın işi değil, framework'ün işidir**. Bir yeteneğin (AI, veritabanı,
SOAP) 3. parti autoconfig'leri, uygulama o yeteneği **açıkça istemedikçe** aday listesine hiç
girmez. Uygulama Spring Boot'un iç paket adlarını bilmek zorunda değildir; yalnız kendi
framework modüllerini (`zeus-ai`, `zeus-database`, `zeus-soap`) pom'una ekler ve tek bir
property yazar.

## API — `zeus.<yetenek>.enabled`

```properties
zeus.ai.enabled=true          # zeus-ai'ı kullanan uygulama
zeus.database.enabled=true    # zeus-database'i kullanan uygulama
zeus.soap.enabled=true        # SOAP endpoint yayınlayan uygulama (zeus-soap)
```

- **Yazılmazsa** (property hiç yok): yetenek KAPALI kalır — REST-only bir uygulama hiçbir şey
  yazmaz ve hata almaz. Bu **meşrudur**, opt-in'den opt-out'a dönüş DEĞİLDİR.
- **`=true`**: yeteneğin 3. parti autoconfig'leri aday listesine girer, normal Spring Boot
  koşullarıyla (`@ConditionalOnClass` vb.) değerlendirilir.
- **`=false`**: yeteneği **bilinçli olarak** kapalı tutmanın yoludur — "bağımlılık WAR'ımda var
  ama bu yeteneği istemiyorum" demenin tek cümlesi. Çelişki SAYILMAZ (aşağıya bakın).
- **Typo'lu bir değer** (`tru`, `evet`, ...): sessizce `false`'a düşürülmez, `IllegalStateException`
  ile açıkça reddedilir — property adı ve verilen değer mesajda görünür.

**Kaçış kapıları:**

| Property | Etki |
|---|---|
| `zeus.autoconfig.filter.enabled=false` | Mekanizmayı **tamamen** kapatır (bu özellik öncesi davranışa döner — hiçbir şey veto edilmez, çelişki denetimi de susar). |
| `zeus.autoconfig.force-include=<Sınıf1,Sınıf2,...>` | Adı verilen autoconfig sınıflarını, yeteneğe ait olsalar bile **hiçbir zaman** veto etmez — yanlış sınıflandırma için kurtarma valfi. |

## Üç parça

1. **Kayıt — `ZeusCapabilities`** (`zeus-base`). Yetenek → property → işaretçi sınıf (dize
   olarak — `zeus-base` yetenek modüllerine derleme zamanında bağlanmaz) → sahiplenilen 3.
   parti autoconfig paket önekleri eşlemesini tutar. Ayrıca `HER_ZAMAN_SERBEST`: hiçbir
   yeteneğe ait olmayan, her uygulamada çalışması gereken yığın (Spring MVC, Jackson,
   validation, **springdoc** — Swagger her uygulamada varsayılan açıktır, bu yetenek DEĞİLDİR).
2. **Filtre — `ZeusAutoConfigurationFilter`** (`zeus-base`, `AutoConfigurationImportFilter`
   olarak `META-INF/spring.factories` ile kayıtlı). Context kurulmadan ÖNCE çalışır; bir
   autoconfig sınıfının sahibi olan yetenek varsa ve o yeteneğin property'si `true` değilse
   veto eder.
3. **Verifier — `ZeusCapabilityVerifier`** (`zeus-base`). "Bağımlılık WAR'da var ama property
   hiç yazılmamış" çelişkisini açılışta yakalar ve açılışı durduran, iki çıkış yolunu da
   söyleyen bir hata fırlatır:

   ```
   Zeus yetenek bildirimi eksik:
         'zeus-database' bağımlılığı bu uygulamanın WAR'ında var ama 'zeus.database.enabled' yazılmamış.
         'database' yeteneği KAPALI kalır ve bean'leri kurulmaz.
         Kullanacaksanız application.properties'e ekleyin:  zeus.database.enabled=true
         Kullanmayacaksanız pom.xml'den zeus-database bağımlılığını kaldırın
         (ya da bilinçli olarak kapalı tutmak için:  zeus.database.enabled=false).
   ```

## Fail-open ↔ fail-closed asimetrisi — ve NEDEN

Bu üç parça **iki farklı** hata felsefesi uygular; bu bilerek yapılmıştır ve gelecekte
"basitleştirilmemesi" gerekir:

- **Çalışma zamanı (filtre) FAIL-OPEN'dır.** `ZeusAutoConfigurationFilter`, tanımadığı bir
  autoconfig sınıfını **asla** veto etmez — sahibi bulunamayan sınıf her zaman geçer.
  Gerekçe: framework'ün eksik bir sınıflandırması (yeni bir 3. parti kütüphane module'e
  girdi ama `ZeusCapabilities`'e henüz eklenmedi), **hâlihazırda çalışan bir uygulamayı**
  asla kırmamalıdır. Sınıflandırma boşluğu üretimde bir kesinti değil, en kötü ihtimalle
  "o yetenek her zaman açık davranıyor" gibi zararsız bir durumdur.
- **Build (guard) FAIL-CLOSED'dır.** `scripts/test-autoconfig-sahipligi.sh`, kurulu
  `com.zeus` module'ündeki **her** autoconfig sınıfının bir yeteneğe ya da
  `HER_ZAMAN_SERBEST`'e düştüğünü denetler; sınıflandırılmamış bir sınıf bulursa **build'i
  kırar**. Gerekçe: sınıflandırma boşluğunun fail-open'da "sessizce zararsız" davranması,
  onun **fark edilmeden büyümesine** izin verirdi — module her yeni 3. parti kütüphaneyle
  büyüdükçe, hiç kimsenin bilmediği "her uygulamada her zaman açık" bir yetenek listesi
  birikir. Guard bu birikimi **derleme anında** görünür kılar; çalışan hiçbir şeyi kırmadan.

İki yarı birlikte şu cümleyi kurar: **"sınıflandırma eksikse üretim asla düşmesin, ama o
eksiklik de asla fark edilmeden kalmasın."** Yalnız fail-open olsaydı sınıflandırma sessizce
çürürdü; yalnız fail-closed olsaydı (ör. filtre de tanımadığı sınıfı veto etseydi) framework'ün
kendi eksik bir güncellemesi tüm uygulamaları aynı anda düşürebilirdi.

## Yetenek tablosu

| Yetenek | `ad` | property | işaretçi sınıf | sahiplendiği autoconfig önekleri |
|---|---|---|---|---|
| AI | `ai` | `zeus.ai.enabled` | `com.zeus.framework.ai.ZeusAiAutoConfiguration` | `org.springframework.ai.` |
| Veritabanı | `database` | `zeus.database.enabled` | `com.zeus.framework.database.ZeusDatabaseAutoConfiguration` | `org.springframework.boot.jdbc.autoconfigure.`, `org.springframework.boot.hibernate.autoconfigure.`, `org.springframework.boot.data.jpa.autoconfigure.`, `org.springframework.boot.persistence.autoconfigure.` |
| SOAP | `soap` | `zeus.soap.enabled` | `com.zeus.framework.soap.ZeusSoapAutoConfiguration` | `org.apache.cxf.spring.boot.autoconfigure.` |

**Her zaman serbest** (yetenek DEĞİL, hiçbir koşulda veto edilmez): Spring Boot çekirdeği
(`org.springframework.boot.autoconfigure.`), web MVC/servlet/Jackson/validation/http/
restclient/webclient/reactor/transaction/data yığınları ve **springdoc** (`org.springdoc.`).

**`zeus-redis` ve `zeus-batch` kayıtta YOK** — kapsam dışı (spec kararı): ikisi de iskelet
hâlde ve `com.zeus` module'üne henüz girmiyor. Gerçek bir modül hâline geldiklerinde "yeni
yetenek eklerken" adımlarını izleyerek kayda eklenmeleri gerekir.

**SOAP'ın önek listesi neden tek satır.** Risk ölçümü (`zeus-sms` üzerinde): gerçek
`ZeusSmsClient`, `JaxWsProxyFactoryBean` ile kendi proxy'sini kurar, `ClientProxy.getClient(...)`
ile `HTTPConduit`'i alır ve zamanlama politikasını **Spring context'i hiç olmadan** uygular —
yani CXF'in Spring autoconfig'ini vetolamak SMS istemcisi için güvenlidir. Bu yüzden `soap`
yeteneği tek öneke (`org.apache.cxf.spring.boot.autoconfigure.`) sahiptir; Spring'e bağımlı
olmayan CXF çekirdeği zaten etkilenmez.

## Yeni yetenek eklerken

Framework'e yeni bir gerçek modül (`zeus-redis`, `zeus-batch` gerçekleştiğinde, ya da başka
bir 3. parti entegrasyonu) eklenirken:

1. `zeus-<ad>` modülünü normal kurala göre oluştur (bkz. CLAUDE.md → "Yeni Modül Ekleme
   Kuralı") ve `Zeus<Ad>AutoConfiguration` + `.imports` dosyasını yaz.
2. `ZeusCapabilities.HEPSI`'ye yeni bir `ZeusCapability` satırı ekle: `ad`, `zeus.<ad>.enabled`,
   `Zeus<Ad>AutoConfiguration`'ın tam adı (dize), ve modülün getirdiği 3. parti autoconfig'lerin
   paket önekleri.
3. `zeus-wildfly-module`'e modülün 3. parti kapanışını ekle (zaten normal akış — modül
   `zeus-base`'e bağlıysa otomatik gelir).
4. `./scripts/install-zeus-module.sh` ile module'ü yenile.
5. `./scripts/test-autoconfig-sahipligi.sh` çalıştır: yeni önek doğru yazılmışsa module'deki
   ilgili sınıflar artık "sınıflandırılmamış" olarak raporlanmaz.
6. Tüketen uygulama pom'una `zeus-<ad>`'ı ekler ve `application.properties`'ine
   `zeus.<ad>.enabled=true` yazar — **hem `src/main` hem `src/test` altında** (aşağıdaki
   tuzağa bakın).

---

## Üç tuzak — plan sırasında sert şekilde öğrenildi

### 1. `src/test/resources/application.properties` gölgeler

Maven, test classpath'inde `src/test/resources/application.properties` varsa onu
`src/main/resources/application.properties`'in **YERİNE** koyar, ÜSTÜNE eklemez. Yetenek
bildirimi yalnız `src/main`'e yazılırsa, `mvn test`/`mvn package` sırasında test properties'i
o bildirimi hiç görmez ve `ZeusCapabilityVerifier` "bağımlılık var ama property yok" diye
build'i kırar (bu gerçekten yaşandı: `spring-wildfly-arch`'ın ilk `mvn package`'ı tam bu
sebeple kırıldı — bkz. `.superpowers/sdd/2026-09-11-yetenek-opt-in-autoconfig/task-5-report.md`
§4). **Her `zeus.<yetenek>.enabled` satırı, uygulamanın `src/test/resources/application.properties`
dosyası varsa orada da tekrar yazılmalıdır.**

### 2. Aynı ada sahip İKİ `ZeusAiAutoConfiguration` sınıfı

Repoda `com.zeus.framework.ai.ZeusAiAutoConfiguration` adında **iki** sınıf vardır:

- `zeus-ai/src/main/java/.../ZeusAiAutoConfiguration.java` — **gerçek** autoconfig sınıfı.
- `zeus-base/src/test/java/com/zeus/framework/ai/ZeusAiAutoConfiguration.java` — `zeus-base`'in
  KENDİ birim testleri için yazılmış, paket-görünür (private değil), **boş** bir sahte sınıf.

Bu tekrar bir hata DEĞİL, bilinçli bir test aracıdır: `ZeusCapabilityVerifier`/
`ZeusAutoConfigurationFilter`, bir yeteneğin WAR'da olup olmadığını `classLoader.loadClass(...)`
ile **gerçek sınıf çözümlemesiyle** anlar (işaretçi sınıf `ZeusCapabilities`'te sadece bir
DİZE'dir — `zeus-base`, `zeus-ai`'a derleme zamanında bağımlı DEĞİLDİR). Birim testlerindeki
sahte `ClassLoader`'lar "izin listesindeki" sınıflar için gerçek yükleyiciye devreder; bu
devrin doğru çalıştığını sınamak için classpath'te GERÇEKTEN yüklenebilir, aynı adlı bir sınıf
gerekir. Bu test-scope sınıf `target/test-classes`'e girer, `target/classes`'e ya da yayınlanan
`zeus-base` jar'ına GİRMEZ — dolayısıyla `zeus-base`'e `zeus-ai` üzerinde gerçek bir bağımlılık
kazandırmaz. Repoda bu sınıfı arayan biri iki eşleşme bulacaktır; kafası karışmasın diye
gerekçe hem o dosyanın javadoc'unda hem burada yazılıdır.

### 3. `EnvironmentPostProcessor` dersi — planın en değerli bulgusu

`ZeusCapabilityVerifier`'ın **ilk sürümü** bir `EnvironmentPostProcessor` olarak
`META-INF/spring.factories`'e kayıtlıydı — çelişki denetiminin "doğal" yeri budur ve gömülü
çalıştırmada (`mvn test`, `spring-boot:run`) **sorunsuz çalışıyordu**: 4/4 birim testi yeşildi.

Gerçek WildFly deploy'u bunun **üretimde hiç çalışmadığını** kanıtladı. İnce WAR modelinde
`spring-boot` jar'ı paylaşımlı `com.zeus` module'ündedir; o classloader WAR'ın
`WEB-INF/lib`'indeki `META-INF/spring.factories` dosyalarını **göremez** — çünkü
`EnvironmentPostProcessor` listesi `spring-boot` jar'ının bulunduğu classloader üzerinden
çözülür. `spring-wildfly-arch`, `zeus.database.enabled` bilerek silinmiş hâlde **sessizce**
deploy oldu; beklenen konuşan hata hiç çıkmadı ve açılış süresi 2.157s → 0.196s'e düştü (JDBC/
Hibernate yığını gerçekten devre dışı kaldığının kanıtı) — yani filtre doğru çalışıyordu, yalnız
denetleyici susuyordu. Ayırt edici deney ve tam kanıt zinciri:
`.superpowers/sdd/2026-09-11-yetenek-opt-in-autoconfig/task-5-report.md` §7 ve §D1-D3.

`ZeusAutoConfigurationFilter` (`AutoConfigurationImportFilter`) ise **aynı dosyada, aynı
kusura düşmeden** çalışıyordu, çünkü `AutoConfigurationImportSelector` filtreleri **bean
(deployment) classloader'ı** ile yükler — ince WAR'da bu, WAR'ın kendi deployment
classloader'ıdır, `WEB-INF/lib`'i görür. Çözüm bu yüzden **denetimi filtrenin içine taşımak**
oldu: `ZeusCapabilityVerifier.denetle(...)`, artık `ZeusAutoConfigurationFilter.match()`
içinden, uygulama başına bir kez çağrılır; `EnvironmentPostProcessor` kaydı ve arayüz
implementasyonu tamamen kaldırıldı. Böylece gömülü çalıştırmada görülen davranışla WildFly'daki
davranış **aynı koddan** gelir — kusurun kendisi tam olarak bu ikisinin AYRIŞMASIYDI.
`scripts/test-spring-factories-ince-war.sh` guard'ı bu kusurun tekrarını önler: `zeus-base`
(ve artık repo genelindeki her modülün) `spring.factories`'ine yalnız ince WAR'da yüklendiği
KANITLANMIŞ tipler (bugün: yalnız `AutoConfigurationImportFilter`) kaydedilebilir;
`EnvironmentPostProcessor` gibi çalışmadığı kanıtlanmış bir tip, module adı + sınıf adı
BİREBİR eşleşen açık bir istisna (`zeus-logger`'ın `CorrelationLoggingEnvironmentPostProcessor`'ı
— telafisi `ZeusServletInitializer`, ayrıntı `18-correlation-id.md`) DIŞINDA kaydedilirse
build'i kırar.

**Genel kural** (zaten `18-correlation-id.md`'de belgeli, burada tekrar edilir çünkü bu planın
kendi kusuru tam olarak bunu doğruladı): ince WAR + `com.zeus` module modelinde
`spring.factories` tabanlı hiçbir Spring uzantısına (`EnvironmentPostProcessor`,
`SpringApplicationRunListener`, `ApplicationContextInitializer`) **güvenilmez** — tek istisna,
Spring'in kendisinin bean classloader'ı ile yüklediği ve gerçek deploy'da kanıtlanmış tipler
(`AutoConfigurationImportFilter` gibi).

### Ek dayanıklılık: `beanClassLoader == null` iken de denetim çalışır

`ZeusAutoConfigurationFilter`, Spring'in kendi
`AutoConfigurationImportSelector#getConfigurationClassFilter`'ıyla AYNI fallback'i uygular:
`beanClassLoader` (Spring gerçek koşuda `invokeAwareMethods` ile HER ZAMAN set eder) `null`
gelirse, sınıfın KENDİ classloader'ı (`getClass().getClassLoader()`) kullanılır. Bu güvenli bir
varsayımdır çünkü `zeus-base` her zaman WAR'ın `WEB-INF/lib`'indedir (zeus jar'ları
`com.zeus`'a GİRMEZ) — dolayısıyla bu sınıfın kendi classloader'ı zaten doğru yükleyicidir.
Önceki sürüm bu dalda denetimi **sessizce atlıyordu**; bu, "denetim ince WAR'da sessizce
çalışmıyor" kusurunu (yukarı bakın) küçük ölçekte yeniden üretirdi. Sözleşme, gerçek
`ZeusAiAutoConfiguration` işaretçisinin (bkz. tuzak 2) `beanClassLoader` HİÇ set edilmeden
çözülebildiği bir testle (`ZeusAutoConfigurationFilterTest.beanClassLoaderYokkenKendiYukleyicisineDuserVeCalisir`)
kilitlenmiştir.
