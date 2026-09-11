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
olmayan bir OpenAI anahtarı istemeyen bir uygulama **12 satırlık** bir workaround bloğuna
(bunun **6**'sı property satırı; sahte `spring.ai.openai.api-key=kullanilmiyor` dahil **5**
workaround property) mahkumdu, (3) daraltma her uygulamada **ayrı ayrı, tutarsız** bir şekilde
tekrarlanıyordu.

> **Sayıların kaynağı** (doküman 08 ile aynı ölçüm, farklı kesit):
> `git -C ../zeus-sample-soap diff -- src/main/resources/application.properties` başlığı
> `@@ -1,14 +1,7 @@` — yani **dosya** 14 satırdan 7 satıra indi; bu 14 satırın **12**'si
> silinen workaround bloğuydu (kalan 2 satır: `spring.application.name` ve bir boş satır).
> Silinen 12 satırın 6'sı property satırıdır (`spring.autoconfigure.exclude=` + 4 devam satırı
> + `api-key`), anlam olarak **5** workaround property eder (4 dışlanan autoconfig + 1 sahte
> anahtar). Doküman 08 dosya kesitini ("14 satırdan 7 satıra, 5 workaround property'den 1
> yetenek bildirimine") söyler; burada blok kesiti verilir. İkisi çelişmez.

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
| `zeus.autoconfig.filter.enabled=false` | **Filtreyi** tamamen kapatır: hiçbir 3. parti autoconfig veto edilmez, çelişki denetimi de susar. Tam olarak neyi geri getirdiği aşağıda — "bu özellik öncesi davranışa döner" **DEĞİLDİR**. |
| `zeus.autoconfig.force-include=<Sınıf1,Sınıf2,...>` | Adı verilen autoconfig sınıflarını, yeteneğe ait olsalar bile **hiçbir zaman** veto etmez — yanlış sınıflandırma için kurtarma valfi. |

> **`zeus.autoconfig.filter.enabled=false` ESKİ DAVRANIŞI GERİ GETİRMEZ — olay anında bunu bilin.**
> Kapattığı yalnızca **filtredir**. Yetenek modüllerinin KENDİ autoconfig'leri
> (`ZeusAiAutoConfiguration`, `ZeusSoapAutoConfiguration`) ayrıca kendi
> `@ConditionalOnProperty(... havingValue = "true")` anotasyonlarını taşır ve bu bayrak onları
> **etkilemez**. Sonuç, ikisinin ortasında bir durumdur:
>
> | | `zeus.ai.enabled` yok + filtre AÇIK (normal) | `zeus.ai.enabled` yok + filtre KAPALI |
> |---|---|---|
> | Spring AI'ın 3. parti autoconfig'leri | veto edilir — sessiz | **çalışır** — ve `api-key` ister (özellik öncesi ağrının ta kendisi) |
> | `ZeusAiAutoConfiguration` (zeus tarafı) | yüklenmez | **yine yüklenmez** (kendi property'si `true` değil) |
>
> Yani bu valf, "zeus opt-in'inin filtresi bir uygulamayı yanlışlıkla düşürdü" senaryosunda
> **3. parti yığını geri açmak** içindir; zeus modüllerini geri açmak için değil. Gerçekten
> özellik öncesi davranışın tamamını isteyen (ve o yığının beklediği property'leri vermeye
> hazır olan) bir uygulama, filtreyi kapatmanın yanında ilgili `zeus.<yetenek>.enabled=true`
> satırlarını da yazmalıdır. Tek sınıflık bir kurtarma için doğru valf zaten
> `zeus.autoconfig.force-include`'tur.

## Üç parça

1. **Kayıt — `ZeusCapabilities`** (`zeus-base`). Yetenek → property → işaretçi sınıf (dize
   olarak — `zeus-base` yetenek modüllerine derleme zamanında bağlanmaz) → sahiplenilen 3.
   parti autoconfig paket önekleri eşlemesini tutar. Ayrıca `HER_ZAMAN_SERBEST`: hiçbir
   yeteneğe ait olmayan, her uygulamada çalışması gereken yığın (Spring MVC, Jackson,
   validation, **springdoc** — Swagger her uygulamada varsayılan açıktır, bu yetenek DEĞİLDİR).
   **`HER_ZAMAN_SERBEST` bir ÇALIŞMA ZAMANI garantisi değildir** — aşağıya bakın.
2. **Filtre — `ZeusAutoConfigurationFilter`** (`zeus-base`, `AutoConfigurationImportFilter`
   olarak `META-INF/spring.factories` ile kayıtlı). Context kurulmadan ÖNCE çalışır; bir
   autoconfig sınıfının sahibi olan yetenek varsa ve o yeteneğin property'si `true` değilse
   veto eder. Fiilen veto ettiği **her yetenek için açılışta TEK bir log satırı** basar
   (aşağıdaki "Veto'nun izi").
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
  Aynı guard İKİNCİ bir ölçüm daha yapar: `HEPSI`'deki her **işaretçi sınıf** dizesinin
  gerçek bir kaynak dosyaya (`zeus-<ad>/src/main/java/<paket>/<Sınıf>.java`) karşılık geldiğini
  doğrular — o dizeler `Class.forName` ile çözülür ve bir yeniden adlandırma/paket taşıma
  onları sessizce kaydırırsa **çelişki denetimi tamamen ölür**: hiçbir test kırılmaz, bildirimi
  eksik uygulama bir daha hata almaz. (Ölçüldü: iki işaretçi adı bozulduğunda guard'lar ve 25
  birim testin tamamı yeşil kalıyordu.)

İki yarı birlikte şu cümleyi kurar: **"sınıflandırma eksikse üretim asla düşmesin, ama o
eksiklik de asla fark edilmeden kalmasın."** Yalnız fail-open olsaydı sınıflandırma sessizce
çürürdü; yalnız fail-closed olsaydı (ör. filtre de tanımadığı sınıfı veto etseydi) framework'ün
kendi eksik bir güncellemesi tüm uygulamaları aynı anda düşürebilirdi.

## Veto'nun izi — yetenek başına tek log satırı

Veto'nun imzası **yokluktur**: veto edilen autoconfig sınıfı aday listesine hiç girmez,
dolayısıyla `debug=true` ile açılan Boot condition raporunda da görünmez (rapor `Exclusions:
None` der — `spring.autoconfigure.exclude` kullanılmadığı için doğrudur ama yanıltıcıdır).
Bu yüzden filtre, **fiilen veto ettiği her yetenek için** açılışta tek bir satır basar:

```
Zeus: 'database' yeteneği KAPALI (zeus.database.enabled yazılmamış) — 13 autoconfig veto edildi. Açmak için: zeus.database.enabled=true
Zeus: 'ai' yeteneği KAPALI (zeus.ai.enabled=false) — 4 autoconfig veto edildi. Açmak için: zeus.ai.enabled=true
```

- **Yetenek başına bir kez** basılır (sınıf başına değil, `match()` çağrısı başına da değil).
- Property hiç yazılmamışsa `... yazılmamış`, bilinçli kapatılmışsa `...=false` yazar — operatör
  hangisi olduğunu logdan görür.
- Veto YOKSA satır da yoktur; sahipsiz (fail-open) sınıflar hiç raporlanmaz.

**Neden gerekliydi:** `zeus-database`'i pom'una yazmayan ama JDBC/JPA'yı **kendi** pom'una
koyan bir uygulamada işaretçi sınıf bulunmaz, dolayısıyla çelişki denetimi de susar — o
uygulamanın tüm persistence yığını veto edilir ve operatörün gördüğü tek şey
`No qualifying bean of type 'JdbcTemplate'` olur; mesajda "zeus" kelimesi **hiç geçmez**.
Bu satır, o durumda zeus'u işaret eden tek izdir. Log **veto EKLEMEZ**, yalnız var olanı
görünür kılar — fail-open kuralı olduğu gibi durur.

## Zeus modüllerinin kendi autoconfig'leri de aynı anahtarla koşulludur

`zeus.<yetenek>.enabled` **tek** kavramdır ve iki tarafı birden yönetir:

| | 3. parti autoconfig | zeus modülünün kendi autoconfig'i |
|---|---|---|
| `ai` | filtre veto eder | `ZeusAiAutoConfiguration` — `@ConditionalOnProperty(havingValue="true")` |
| `soap` | filtre veto eder | `ZeusSoapAutoConfiguration` — `@ConditionalOnProperty(havingValue="true")` |

Bu **simetri zorunludur**, kozmetik değildir: `ZeusSoapAutoConfiguration`'ın bean'leri CXF
`Bus`'ına bağlıdır ve `Bus`'ın tek sağlayıcısı, `soap` yeteneğine ait olan
`CxfAutoConfiguration`'dır. Zeus tarafı yalnız `@ConditionalOnClass(Bus.class)` ile koşullu
kaldığı sürece, `zeus.soap.enabled=false` yazan bir uygulama — ki bu cümleyi framework'ün
**kendi hata mesajı** öneriyor — açılışta
`NoSuchBeanDefinitionException: No qualifying bean of type 'org.apache.cxf.Bus'` alırdı.
Ölçülüp düzeltildi (fix round 2); sözleşme
`zeus-soap/src/test/java/com/zeus/framework/soap/ZeusSoapAutoConfigurationTest.java` ile
kilitlendi.

`zeus-database` bugün bu simetriyi **taşımıyor** (kendi autoconfig'leri `zeus.database.enabled`
ile koşullu değil); bu, bilinçli olarak ayrı ele alınan açık bir sorudur. Bugün için zararsız
olmasının sebebi, o modülün bean'lerinin `@ConditionalOnBean(EntityManagerFactory.class)` ve
`@ConditionalOnSingleCandidate(DataSource.class)` ile koşullu olmasıdır: yetenek kapalıyken
`DataSource`/`EntityManagerFactory` hiç kurulmaz, dolayısıyla zeus bean'leri de sessizce
kurulmaz — CXF `Bus`'ındaki gibi **zorunlu bir constructor bağımlılığı** yoktur, açılış düşmez.

## Yetenek tablosu

| Yetenek | `ad` | property | işaretçi sınıf | sahiplendiği autoconfig önekleri |
|---|---|---|---|---|
| AI | `ai` | `zeus.ai.enabled` | `com.zeus.framework.ai.ZeusAiAutoConfiguration` | `org.springframework.ai.` |
| Veritabanı | `database` | `zeus.database.enabled` | `com.zeus.framework.database.ZeusDatabaseAutoConfiguration` | `org.springframework.boot.jdbc.autoconfigure.`, `org.springframework.boot.hibernate.autoconfigure.`, `org.springframework.boot.data.jpa.autoconfigure.`, `org.springframework.boot.persistence.autoconfigure.` |
| SOAP | `soap` | `zeus.soap.enabled` | `com.zeus.framework.soap.ZeusSoapAutoConfiguration` | `org.apache.cxf.spring.boot.autoconfigure.` |

**Her zaman serbest** (yetenek DEĞİL): Spring Boot çekirdeği
(`org.springframework.boot.autoconfigure.`), web MVC/servlet/Jackson/validation/http/
restclient/webclient/reactor/transaction/data yığınları ve **springdoc** (`org.springdoc.`).

> **`HER_ZAMAN_SERBEST`'i BUILD listesi olarak okuyun, çalışma zamanı garantisi olarak DEĞİL.**
> `ZeusAutoConfigurationFilter` bu listeyi **hiç okumaz**; okuyan tek yer
> `scripts/test-autoconfig-sahipligi.sh` guard'ıdır (her autoconfig sınıfı ya bir yeteneğe ya
> da buraya düşmeli). Çalışma zamanındaki serbestlik başka bir şeyden gelir: **sahibi
> olmamaktan** — `ZeusCapabilities.sahipBul(...)` önce `HEPSI`'ye bakar, hiçbir yetenek
> sahiplenmiyorsa sınıf geçer (fail-open).
>
> Pratik sonucu şudur: yanlışlıkla bir yeteneğe düşmüş bir sınıfı serbest bırakmak için
> önekini `HER_ZAMAN_SERBEST`'e eklemek **hiçbir şeyi değiştirmez** — guard yeşil kalır ama
> davranış aynıdır, çünkü `sahipBul` yine `HEPSI`'de bir sahip bulur. Doğru düzeltme
> `HEPSI`'deki hatalı öneki **daraltmak/kaldırmaktır**; tek bir sınıf için acil kurtarma valfi
> ise `zeus.autoconfig.force-include`'tur.

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
`AutoConfigurationImportSelector#checkExcludedClasses`'ıyla AYNI fallback'i uygular (bu deseni
taşıyan `getConfigurationClassFilter` DEĞİL — o, `beanClassLoader`'ı null kontrolsüz geçirir;
fallback aynı sınıfın `checkExcludedClasses` metodunda yaşar):
`beanClassLoader` (Spring gerçek koşuda `invokeAwareMethods` ile HER ZAMAN set eder) `null`
gelirse, sınıfın KENDİ classloader'ı (`getClass().getClassLoader()`) kullanılır. Bu güvenli bir
varsayımdır çünkü `zeus-base` her zaman WAR'ın `WEB-INF/lib`'indedir (zeus jar'ları
`com.zeus`'a GİRMEZ) — dolayısıyla bu sınıfın kendi classloader'ı zaten doğru yükleyicidir.
Önceki sürüm bu dalda denetimi **sessizce atlıyordu**; bu, "denetim ince WAR'da sessizce
çalışmıyor" kusurunu (yukarı bakın) küçük ölçekte yeniden üretirdi. Sözleşme, gerçek
`ZeusAiAutoConfiguration` işaretçisinin (bkz. tuzak 2) `beanClassLoader` HİÇ set edilmeden
çözülebildiği bir testle (`ZeusAutoConfigurationFilterTest.beanClassLoaderYokkenKendiYukleyicisineDuserVeCalisir`)
kilitlenmiştir.
