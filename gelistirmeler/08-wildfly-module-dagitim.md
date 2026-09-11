# 08 — WildFly Paylaşımlı Module Dağıtımı (`zeus-wildfly-module` + install scripti)

Bu doküman, WildFly'daki **tek ve paylaşımlı `com.zeus` module**'ünün nasıl üretildiğini ve
neden bu sorumluluğun **platform'a (zeus-fw)** ait olduğunu tanımlar.

## Neden burada (zeus-fw), uygulamada değil

3. parti kütüphaneler (Spring, Spring Boot, Hibernate, Jackson, ...) hiçbir uygulamanın WAR'ına
konmaz; WildFly'daki **tek `com.zeus` module**'üne konur ve onu kullanan **tüm uygulamalar** oradan
yüklenir (ince WAR mimarisi — bkz. `spring-wildfly-arch/gelistirmeler/08-com-zeus-module.md`).

Module paylaşımlı olduğundan, onu üreten iş de **tek ve merkezi** olmalıdır — her uygulamaya
kopyalanmamalı. Bu yüzden hem bağımlılık sözleşmesi hem üretim scripti zeus-fw'dadır:

| Bileşen | Yer | Görev |
|---------|-----|-------|
| `zeus-wildfly-module` (pom modülü) | `zeus-fw/zeus-wildfly-module/` | Module'e girecek 3. parti runtime bağımlılıklarının **birleşimi** (union). |
| `install-zeus-module.sh` | `zeus-fw/scripts/` | Bu modülün runtime kapanışını çözüp `com.zeus` module'ünü üretir. |
| `deploy.sh` | her uygulamada (`<app>/scripts/`) | Yalnız o uygulamanın ince WAR'ını build + deploy eder (per-app, doğru). |

## Sorumluluk Sınırı (Governance) — kim ne yapabilir

Uygulamalar **yalnızca tüketicidir**: WildFly'a **sadece kendi ince WAR'larını deploy ederler**.
Module ile ilgili her şey framework'e (platform) aittir.

| Yetenek | Sahip | Nasıl zorlanır |
|---------|-------|----------------|
| WAR'dan 3. parti jar'ı dışlama (ince WAR) | **Framework** | `zeus-parent` `maven-war-plugin` `packagingExcludes` → `${zeus.war.packaging-excludes}`. Değer **ÜRETİLİR**, elle yazılmaz: `scripts/generate-war-excludes.sh` onu paylaşımlı module'ün bağımlılık sözleşmesinden basar (2026-09-11'de ölçüldü: **193 alternatif = 184 module kapanışından + 9 sabit-kuyruk kalıbı**; standard ve soap listeleri bugün karakter karakter özdeş — CXF `com.zeus`'a taşındığı için, bkz. aşağıdaki "Neden CXF artık `com.zeus`'ta"). Uygulama configsiz miras alır. |
| Paylaşımlı `com.zeus` module'ünü oluşturma/güncelleme | **Framework** | `zeus-fw/scripts/install-zeus-module.sh` yalnızca bu repodadır; uygulamalarda module üretim aracı **yoktur**. |
| Module'e jar ekleme | **Framework** | İçerik `zeus-wildfly-module` bağımlılık sözleşmesinden gelir; uygulama `modules/`'a yazmaz. |
| WAR deploy | **Uygulama** | Her uygulamanın kendi `scripts/deploy.sh`'ı yalnızca `standalone/deployments/`'a kopyalar. |

> **POLARİTE — ALLOWLIST DEĞİL, DENYLIST.** Yukarıdaki satır bir dönem `%regex[WEB-INF/lib/(?!zeus-).*\.jar]`
> yazıyordu: "`zeus-` ile başlamayan HER ŞEY atılır" (allowlist). O kural **kaldırıldı**, çünkü
> paylaşımlı module'de OLMAYAN bir bağımlılık da sessizce siliniyor ve WildFly'da
> `NoClassDefFoundError` üretiyordu. Bugünkü kural tersidir: **"module'ün verdiğini at, kalan
> her şeyi WAR'da taşı."** Liste bu yüzden module sözleşmesinden ÜRETİLİR — aynı kümeyi iki
> yerde tutmak drift'in tanımıdır. Gerekçe: `19-war-paketleme-module-farkindaligi.md`.

**Kural:** Uygulamalar `maven-war-plugin` yapılandırmasını **override etmemeli** (fat WAR yasak) ve
WildFly `modules/` dizinine **dokunmamalıdır**. Bu sınır bugün yapısal olarak sağlanır (uygulamada
module aracı yok, WAR dışlama framework'ten miras). İstenirse CI'da sert kontrol (örn. uygulama
pom'unda `maven-war-plugin` `<configuration>` varsa veya WAR'da zeus-dışı jar bulunursa build'i
kır) ek bir güvence olarak eklenebilir.

### App-specific dependency (WAR-bundle istisnası)

Bir uygulama, **framework'te/paylaşımlı module'de olmayan** ve **yalnızca kendi kullandığı** bir
kütüphaneye ihtiyaç duyabilir. İki seçenek vardır:

| Kütüphane türü | Nereye | Nasıl |
|----------------|--------|-------|
| **Genel/paylaşılan** (≥2 app) | Paylaşımlı `com.zeus` module | `zeus-wildfly-module/pom.xml`'e ekle → module'ü yeniden üret + restart. |
| **App'e özel** (yalnız 1 app) | O app'in **kendi WAR'ı** (WEB-INF/lib) | Hiçbir şey yazılmaz — otomatik. |

> **App-specific bağımlılık.** Yalnız bir uygulamanın kullandığı ve paylaşımlı module'e
> konması gerekmeyen bir kütüphane, hiçbir şey yazılmadan **WAR'da taşınır**: dışlama
> listesi yalnız module'ün sağladığı jar'ları kapsar, gerisi otomatik WAR'a girer
> (`19-war-paketleme-module-farkindaligi.md`). Eskiden bunun için `zeus.war.keep` ile elle
> önek yazmak gerekiyordu; o property kaldırıldı.

**Neden module'e değil WAR'a:** App'e özel lib paylaşımlı module'e konursa **diğer tüm uygulamalara
dayatılır** (gereksiz şişme + lockstep yükü). WAR'a konunca yalnız o app'i etkiler. WildFly'da çalışır
çünkü app kodu + `WEB-INF/lib/<lib>` **aynı WAR classloader'ındadır** (kısıt yalnızca "module sınıfları
WAR'ı göremez"; app-specific lib'i sadece app kodu kullandığından sorun değil).

> Uyarı: WAR'da taşınan bir lib **module'e girmediği** için, onu **module'deki bir sınıf
> kullanamaz** (module → WAR görünmez). Yalnız app'in kendi kodu kullanabilir. Module'deki Spring/Hibernate
> gibi bir bileşenin görmesi gereken bir lib ise → o, app-specific değildir; `zeus-wildfly-module`'e konmalı.

### Module kapsam kontrolü (deploy guard) — bugün ne yapıyor

WAR paketlemesi **denylist**'e geçtiğinden beri (`19-war-paketleme-module-farkindaligi.md`),
module'de olmayan bir bağımlılık artık "eksik" değildir — **otomatik olarak WAR'ın içinde
taşınır**. Bu yüzden eskiden var olan "app'in kapanışındaki her jar module'de mi?" kontrolü
**kaldırıldı**: bugün onu çalıştırmak, gerçekte deploy'u kırmayacak bir durumu yanlış pozitif
olarak işaretlerdi.

`zeus-fw/scripts/verify-module-coverage.sh <app-dir>`, deploy'dan önce hâlâ **üç gerçek hatayı**
yakalar:

1. **Slot kurulu mu?** — uygulamanın hedeflediği `com.zeus` slot'u (`zeus.module.slot`) —
   SOAP tipinde ayrıca `com.zeus.soap` slot'u (`zeus.soap.module.slot`) — sunucuda kurulu
   değilse, kriptik bir açılış hatası yerine net mesajla (hangi komutla kurulacağı dahil)
   burada durur.
2. **Descriptor üretilmiş mi?** — WAR'da framework'ün ürettiği
   `jboss-deployment-structure.xml` yoksa (`zeus-generated-descriptor` profili devreye
   girmemiştir), WAR WildFly'da `com.zeus`'u hiç göremez; bu da burada erken yakalanır.
3. **Ters kapsam: sildiğimiz şey sunucuda VAR mı?** — `zeus.war.packaging-excludes`
   içindeki her artifactId'nin hedeflenen slot'ta (SOAP'ta birleşimde) fiilî bir jar'ı
   olmalı. Dışlama listesi çalışma ağacındaki kapanıştan üretilir; module yalnız
   staging'e kurulmuşsa ya da app eski bir immutable slot'u hedefliyorsa, o slot'ta
   OLMAYAN bir jar WAR'dan atılır → `NoClassDefFoundError`. Sabit kuyruk girdileri
   (`ojdbc*`, `jakarta.*-api`, `lombok`, jarmode, gömülü tomcat) module'de bilerek
   yoktur ve bu kontrolün dışındadır.

Self-contained WAR'larda (`zeus.war.packaging-excludes` boş — standalone/BFF tipi) denetim
tamamen atlanır: com.zeus module'ü zaten kullanılmaz. Her uygulamanın **`deploy.sh`'ı bu
kontrolü WAR'ı kopyalamadan önce çağırır**; `SKIP_COVERAGE=1` ile atlanabilir.

**Kaybedilen şey:** artık `zeus-wildfly-module`'ün uygulamaların runtime ihtiyacının gerisine
düşmesini (drift) otomatik yakalayan bir ağ **yok**. Bir app, module'de olmayan bir bağımlılığı
sessizce WAR'ında taşımaya başlayabilir ve kimse fark etmeyebilir. Bu bir gözden kaçma değil,
**bilinçli kabul edilen bir taviz**dir — uyarı mekanizması istenmediği için alınmış bir karardır
(bkz. `19-war-paketleme-module-farkindaligi.md` → "Bilinçli kabul edilen taviz").

> **Denetimin SINIRI (zeus-ai eklenirken öğrenildi):** kalan kontroller "slot kurulu mu?" ve
> "descriptor üretilmiş mi?" sorularını cevaplar, "sınıflar **link olur mu**?" sorusunu
> cevaplayamaz. Module'e yeni bir Spring modülü girdiğinde (ör. Spring AI ile gelen
> `spring-webflux`), o modülün ihtiyaç duyduğu **jakarta API module'leri** de üretilen
> module.xml'in `<dependencies>` listesinde olmalıdır — yoksa deploy POST_MODULE anotasyon
> taramasında `NoClassDefFoundError` ile düşer (yaşanan örnek: `jakarta.websocket.Endpoint`).
> Ayrıntı: `15-zeus-ai.md`.

## `zeus-wildfly-module` — bağımlılık sözleşmesi

`packaging=pom`, parent `zeus-parent`. Jar üretmez; sadece **module'e girecek bağımlılıkları**
declare eder. Sürümler zeus BOM'dan gelir, burada yazılmaz.

**ÖNEMLİ kural:** Bu modül, `com.zeus` module'ünü paylaşan **tüm uygulamaların** runtime 3. parti
bağımlılıklarının **birleşimini** içermelidir. Yeni bir uygulama burada olmayan bir runtime
kütüphanesi kullanıyorsa, o bağımlılık önce buraya eklenir, sonra module yeniden üretilir; aksi
halde o uygulama WildFly'da `NoClassDefFoundError` alır. (Bugün tek tüketen `spring-wildfly-arch`
olduğundan içerik onun runtime starter'larını yansıtır: web, validation, data-jpa, springdoc;
gömülü Tomcat `provided` ile dışlanır — WildFly Undertow kullanır.)

**Sözleşme iki kaynaktan beslenir** (2026-08-28'den beri):

1. **Zeus modülleri — otomatik.** `zeus-wildfly-module`, `zeus-base` / `-logger` / `-database` /
   `-service` / `-ai` modüllerini bağımlılık olarak alır. Amaç zeus jar'larını module'e koymak
   DEĞİL (`install-zeus-module.sh` `EXCLUDE_REGEX` ile `zeus-*` jar'larını atar; onlar WAR'da
   taşınır) — tek işlevi bu modüllerin **3. parti kapanışını** module'e taşımaktır. Böylece bir
   zeus modülüne yeni bir kütüphane eklendiğinde burayı elle aynalama ihtiyacı kalmaz.
   Doğrulama: `slf4j-api` artık `zeus-base` üzerinden, `spring-orm` `zeus-database` üzerinden
   çözülüyor (`mvn -pl zeus-wildfly-module dependency:tree -Dincludes=org.slf4j:slf4j-api`).
2. **Uygulamaların doğrudan kullandığı yığın — elle.** Hiçbir zeus modülünün getirmediği
   şeyler (ör. `springdoc`) burada açıkça sayılır.

`zeus-redis` ve `zeus-batch` **bilerek dışarıdadır** (+50 ve +33 artefakt; ikisi de iskelet ve
tüketeni yok — paylaşımlı module'e girmeleri, kullanmayan tüm uygulamalara restart lockstep
maliyeti bindirir). `zeus-soap` (framework jar'ının kendisi) da girmez — hiçbir `zeus-*` jar'ı
girmez (genel kural, `EXCLUDE_REGEX`). **CXF'in KENDİSİ (3. parti yığın) ise 2026-09-11'den beri
`com.zeus`'un içindedir** — `zeus-wildfly-module/pom.xml` `cxf-spring-boot-starter-jaxws`'ı
doğrudan bildirir. Bu, önceki bir kararın (CXF ayrı `com.zeus.soap` module'ünde kalsın)
**tersine dönmesidir**; güncel gerekçe aşağıdaki "Neden CXF artık `com.zeus`'ta" bölümünde.

**Module'e hiç bağlanmayan uygulamalar.** Birleşim kuralının doğal sınırı şudur: yalnız bir
uygulamanın kullandığı ve module'e konsa herkese dayatılacak kütüphaneler (tipik olarak
auth/authorization server'ın Spring Security + JOSE yığını). Böyle bir uygulama
`zeus-standalone-parent`'ı seçer: self-contained WAR üretir, descriptor'da `com.zeus`
bağımlılığı olmaz ve module'ün **kurulu olmadığı** bir WildFly'a bile deploy edilir
(doğrulandı). `verify-module-coverage.sh` bu uygulamalarda denetimi atlar. Seçim kriteri:
`14-uygulama-tipi-parentlar.md` → "Standalone hattı".

**Oracle sürücüsü — kapanışa girer ama module'e GİRMEZ.** `zeus-database`, `ojdbc17`'yi
compile scope'ta bildirir; amaç uygulamaların sürücüyü tekrar tekrar yazmaması ve `local`
profilin (embedded Tomcat, doğrudan JDBC) hiçbir ek bildirim olmadan çalışmasıdır. Sürücünün
`com.zeus`'a kopyalanması ise YASAK: WildFly'ın kendi `com.oracle.ojdbc` module'ü datasource'a
bağlıdır; ikinci bir kopya olursa JNDI'dan gelen `Connection` bir classloader'ın sınıfı,
uygulamanın gördüğü tip diğerininki olur → `ClassCastException`/`LinkageError`. Bu yüzden
`ojdbc[0-9]+|orai18n|ucp[0-9]+`, her iki scriptin `EXCLUDE_REGEX`'inde `jakarta.*-api` ile
aynı muameleyi görür. Sürücünün yolculuğu: **compile'da var → WAR'da yok → module'de yok →
WildFly'da sunucunun module'ünden**.

**Bu kural jar KOPYASI içindir, module IMPORT'u için değil.** Devralınan bir util katmanı
sürücü sınıflarına kod olarak bağlıysa (`Class.forName`, `OracleConnection` unwrap,
`OracleTypes.CURSOR`) ince WAR'da deploy `could not load JDBC driver class` ile düşer. Çözüm,
sunucunun **kendi** `com.oracle.ojdbc` module'ünü deployment descriptor'ına import etmektir:
JCA'nın kullandığı module'ün aynısı olduğu için sınıf kimliği tek kalır, çakışma olmaz.
Mekanizma opt-in bir property'dir (`zeus.descriptor.extra.modules`) —
`10-versiyonlu-slot-uretilen-descriptor.md`. Yasak olan hâlâ jar'ı WAR'a veya `com.zeus`'a
**kopyalamaktır**.

**Module geniştir, uygulama dardır — daraltma uygulamanın işidir.** Module tüm uygulamaların
birleşimi olduğu için, bir uygulama kullanmadığı yeteneklerin jar'larını da classpath'inde
görür ve **Spring Boot onları otomatik yapılandırmaya çalışır**. Veritabanı kullanmayan bir
uygulama `Failed to determine a suitable driver class`, AI kullanmayan bir uygulama
`At least one credential source must be specified` ile deploy'da düşer. Çözüm uygulamada:

```properties
spring.autoconfigure.exclude=\
  org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,\
  org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration
```

(ya da ilgili yeteneğin beklediği minimum property'yi vermek). Bu, ince WAR modelinin
kaçınılmaz bedelidir; module'ü uygulama başına daraltmak paylaşımlılığı bozardı.

Detay ve yenileme prosedürü: `17-module-yenileme-runbook.md`.

## Üretim — `scripts/install-zeus-module.sh`

```bash
cd zeus-fw
./scripts/install-zeus-module.sh                                  # varsayılan WILDFLY_HOME, slot: main
./scripts/install-zeus-module.sh --slot 1.1.0                     # versiyonlu slot (immutable; restart'sız kurulum)
WILDFLY_HOME=/path/staging-wildfly ./scripts/install-zeus-module.sh   # staging hedefi
```

> **Versiyonlu slot'lar:** sürüm/CVE geçişlerinde lockstep'i ve restart zorunluluğunu kırmak için
> module sürüm başına ayrı slot'a kurulabilir (`modules/com/zeus/<slot>/`); uygulamalar üretilen
> descriptor'larındaki slot ile bağlanır. Detay: `10-versiyonlu-slot-uretilen-descriptor.md`.

Akış: `dependency:copy-dependencies` (zeus-wildfly-module, runtime scope) → jar'ları
`${WILDFLY_HOME}/modules/com/zeus/main/`'e kopyala (jakarta-api / lombok / jarmode / **zeus-***
dışlanır) → her jar'a Jandex index göm → `module.xml` üret.

- **Hedef sunucu `WILDFLY_HOME` ile seçilir** → aynı script staging ve prod'a karşı çalışır.
- **module.xml değişince WildFly RESTART şart** (module tanımı cache'li).
- Yalnız zeus/uygulama kodu değişince module değişmez (zeus jar'ları WAR'da) → WAR redeploy yeter.

## CVE yamasıyla ilişki

CVE yaması bu module üzerinden uygulanır: sürüm/override **`zeus-dependencies` BOM**'da yapılır,
sonra bu script module'ü yeniden üretir. Tek module = tek Spring sürümü = tüm uygulamalar lockstep.
Süreç (staging geçidi, gate'ler, rollback): `gelistirmeler/09-cve-guvenlik-yamalama.md`.

## Yeni uygulama eklenince yapılacaklar

1. Uygulamanın runtime 3. parti bağımlılıklarından `zeus-wildfly-module`'de **olmayan** var mı kontrol et.
2. Varsa `zeus-wildfly-module/pom.xml`'e ekle.
3. `install-zeus-module.sh` ile module'ü yeniden üret + WildFly restart.
4. Uygulamanın `deploy.sh`'ı ile WAR'ı deploy et.

## Neden CXF artık `com.zeus`'ta (2026-09-11, önceki karardan DÖNÜŞ)

Bu dokümanın önceki sürümleri "CXF yığını ayrı `com.zeus.soap` module'üne aittir" diyordu.
2026-09-11'de bu karar **tersine döndü**: `zeus-wildfly-module/pom.xml`
`cxf-spring-boot-starter-jaxws`'ı doğrudan bildiriyor; CXF artık `com.zeus`'un **kendi**
runtime kapanışında.

**Eski gerekçe neydi:** SMS'i (veya başka bir SOAP istemcisini) hiç kullanmayan uygulamalar
CXF'in ~13 jar'lık kapanışını taşımasın, `com.zeus` lockstep'i (her değişiklikte tüm
uygulamaların ortak restart maliyeti) ağırlaşmasın diye CXF ikinci, opt-in bir module'de
(`com.zeus.soap`) tutuluyordu (`20-zeus-sms.md`'deki ilk karar).

**Yeni gerekçe — bu maliyeti göze almaya değer:** ikinci module'ün varlığı, onu kullanan HER
sunucuya **ikinci bir kurulum adımı** (`install-zeus-module.sh --module soap`) dayatıyordu ve bu
adım unutulabilir bir operasyonel adımdı — SOAP tipi bir uygulama deploy edilmeden önce
`com.zeus.soap` slot'unun da kurulu olduğunu ayrıca doğrulamak gerekiyordu
(`verify-module-coverage.sh`'ın "slot kurulu mu?" kontrolü bunun için vardı). `com.zeus` ZATEN
her sunucuda kuruludur (module'ler arasında EN AZ opsiyonel olanı) — CXF'i oraya taşımak
ikinci kurulum adımını YAPISAL olarak imkânsız kılıyor: artık kurulacak "ikinci bir şey" yok.
Bedeli ölçüldü ve kabul edildi: `com.zeus` 157 → **180** jar'a büyüdü (13'ü CXF) — SMS'i hiç
kullanmayan bir uygulama da bu 13 jar'ı classpath'inde görür (zararsız — module geniş,
uygulama dar kuralı zaten böyle işliyor, bkz. yukarıdaki "Module geniştir, uygulama dardır").

## com.zeus.soap — artık BOŞ bir module (küme farkı ∅) — ve bunun İKİ sonucu

SOAP uygulamaları (parent: `zeus-soap-parent`) hâlâ `com.zeus.soap`'ı import eder:

```bash
./scripts/install-zeus-module.sh --module soap [--slot X] [--base-slot Y]
```

ama içeriği artık **0 jar**dır. Script CXF'in TAM kapanışını toplar, sonra `com.zeus`'un
kapanışıyla **küme farkı** alır (`CXF kapanışı EKSİ com.zeus kapanışı`); CXF `com.zeus`'a
taşındığından beri bu fark **boş küme**dir (ölçüldü, 2026-09-11: 180 jar'lık ham CXF kapanışının
tamamı — 64 jar — "temel com.zeus module'ünde zaten var" diye atlandı, 0 jar kopyalandı).

`com.zeus.soap` **niçin hâlâ var, tamamen silinmedi:** SOAP tipinin descriptor'ı
(`zeus-war-defaults/.../descriptor-soap/jboss-deployment-structure.xml`) hâlâ
`<module name="com.zeus.soap" .../>` import eder ve `webservices` subsystem'ini dışlar — bu
yapısal ayrım (SOAP tipi vs standart tip) korunuyor; module'ün boş olması bu ayrımı geçersiz
kılmıyor, yalnız o ayrımın taşıdığı jar sayısını sıfıra indiriyor. `test-module-liste-esitligi.sh`
de bunu bir "eksiklik" değil **meşru bir durum** olarak ele alır (soap çifti `com.zeus ∪
com.zeus.soap` birleşimine bakar; birleşim zaten `com.zeus`'un kendisiyle özdeştir).

**Boş bir module'ün KENDİ BAŞINA yeterli olmadığı — bu satırla DURMAYIN.** Bir module'ün
"0 jar" olması, o module'ü kurmanın zararsız bir hiç-bir-şey-yapmama olduğu anlamına GELMEZ.
2026-09-11'de gerçek bir deploy denemesinde bu iki gerçek şu şekilde birleşti ve gerçek bir
regresyona yol açtı — ikisi COUPLE edilmeden okunursa yeniden açılabilir:

1. **Boş dizinde `*.jar` glob'u kendi metniyle genişler** (bash varsayılanı) →
   `<resource-root path="*.jar"/>` module.xml'e SAHTE bir satır olarak yazılıyordu → WildFly
   böyle bir module'ü hiç yüklemiyor ("resource root not found") → SOAP tipi HER deploy
   düşerdi. Düzeltme: `shopt -s nullglob` + ham kapanışın (küme farkından ÖNCEKİ) boş olması
   ayrı bir hata olarak ele alınır (ölçüm hatasını meşru ∅ farkından ayırmak için).
2. **Jar'sız bir module'ün gömülü Jandex index'i de yoktur** — ve WildFly'ın `AnnotationIndexSupport`
   sınıfı bir module'de HİÇ `META-INF/jandex.idx` bulamazsa, HIZLI yoldan (module classloader'ından
   index okuma) GERİ DÜŞÜYOR: module'ün erişebildiği TÜM `.class` kaynaklarını —  **bağımlı olduğu
   `com.zeus`'un 180 jar'ı DAHİL** — TEK bir Jandex `Indexer`'da HAM olarak indexliyor. 512m
   varsayılan heap'te bu **`OutOfMemoryError`** ile PARSE fazında patlıyor — yani `com.zeus.soap`
   BOŞ olduğu İÇİN, ona bağlı SOAP tipi HER deploy düşüyordu (ampirik olarak doğrulandı, stack
   trace `org.jboss.jandex.Indexer.index` → `AnnotationIndexSupport.calculateModuleIndex`).

**Bu iki gerçek AYRI ele alınamaz: "module artık boş" cümlesi TEK BAŞINA, gelecekte
`empty-index` mantığını "artık gereksiz karmaşıklık" diye SİLDİRECEK cümledir.** Doğru okuma:
"module boş OLDUĞU İÇİN, WildFly'ın index bulamayınca düştüğü geri-düşüş yolu `com.zeus`'un
TAMAMINI ham tarar; bu yüzden boş bir module'e bile GEÇERLİ (ama boş) bir Jandex index'i
KOYULMAK ZORUNDADIR." Çözüm — `install-zeus-module.sh`'ın 4b adımı — `copied == 0` olduğunda
module'e **dizin tipi** bir resource-root (`empty-index/META-INF/jandex.idx`, 21 bayt, 0 sınıf,
`io.smallrye:jandex:3.2.0` ile üretilir ve WildFly'ın kendi `jandex-3.6.0` `IndexReader`'ıyla
doğrulanmıştır) ekler; JAR değil DİZİN seçilmesinin nedeni de aynı coupling'in bir parçasıdır:
module dizinindeki her JAR, `test-module-liste-esitligi.sh`'ın ölçtüğü module↔liste eşitliğinde
karşılığı olması gereken bir artifact sayılır — sentetik bir jar o eşitliği bozardı; DİZİN bu
guard'ın kapsamı dışındadır (yalnız `*.jar`'a bakar), dolayısıyla `empty-index/` eşitliği
etkilemeden index sorununu çözer.

- Sözleşme: `zeus-soap-wildfly-module/pom.xml` (cxf-spring-boot-starter-jaxws; sürüm BOM'dan;
  CXF kapanışı `com.zeus`'la örtüştüğü için bugün fiilen `com.zeus`'un bir ALT KÜMESİ).
- Jar seti = CXF kapanışı **EKSİ** com.zeus kapanışı = **bugün ∅** (ölçüldü, 2026-09-11).
- Üretilen module.xml `com.zeus`'a (--base-slot) bağımlıdır; slot politikası com.zeus ile aynıdır.
- Kapsam denetimi: `verify-module-coverage.sh` SOAP uygulamalarında com.zeus ∪ com.zeus.soap
  birleşimine bakar; `test-module-liste-esitligi.sh` (yeni, `wf` etiketli guard) kurulu module(ler)
  ile üretilen listenin İKİ YÖNLÜ eşitliğini ölçer (`A \ B` → çift kopya riski, `B \ A` →
  `NoClassDefFoundError` riski). Ölçülen (2026-09-11): **180 module jar'ı ∧ 172 dışlanan
  artifactId — fark YOK**, hem standard hem soap çiftinde.

Detay: `gelistirmeler/14-uygulama-tipi-parentlar.md`, `.superpowers/sdd/2026-09-11-cxf-com-zeus-ve-script-sertlestirme/task-3-report.md`
(Bulgu 1 ve Bulgu 2 — bu iki arızanın birebir teşhis kaydı).
