# 10 — Versiyonlu Module Slot'ları + Üretilen Descriptor (platform)

Bu doküman iki birbirini tamamlayan mekanizmayı tanımlar:

1. **Versiyonlu slot'lar** — paylaşımlı `com.zeus` module'ünün sürüm başına immutable
   kopyaları (`modules/com/zeus/<slot>/`). CVE/sürüm geçişlerinde **lockstep'i ve restart
   zorunluluğunu** kırar (bkz. `09-cve-guvenlik-yamalama.md` §7'deki yönetsel gerçek).
2. **Üretilen descriptor** — uygulamaların `WEB-INF/jboss-deployment-structure.xml`'i
   **app repo'sunda tutulmaz**; build sırasında framework şablonundan üretilir ve slot
   değeri otomatik doldurulur. Developer bu dosyayı ne yazar ne görür.

## Neden

Tek `com.zeus` module'ünde bir Spring CVE yaması iki kuplaj üretir:

| Kuplaj | Sonuç |
|--------|-------|
| **Lockstep** | Tek module = tek Spring sürümü → en yavaş doğrulanan app yamayı herkes için geciktirir. |
| **Restart** | Yüklü module'ün tanımı cache'lenir → her yama tam sunucu restart'ı = tüm app'lere kesinti. |

WildFly'ın yerleşik **slot** mekanizması ikisini birden çözer: aynı module adının sürüm
başına ayrı dizini olur (`com.zeus:main`, `com.zeus:1.1.0`, ...). Kritik davranış:
**yeni bir slot dizini eklemek restart gerektirmez** (module'ler ilk referansta tembel
yüklenir; cache sorunu yalnızca *yüklü* bir module'ün içeriği değişince oluşur — slot'lar
immutable olduğundan o duruma hiç düşülmez). Her app, WAR'ındaki descriptor'la kendi
slot'unu seçer ve kendi hızında geçer.

## Parçalar

### 1) `zeus-war-defaults` — descriptor şablonu (yeni modül)

`jboss-deployment-structure.xml`'in **tek doğru kopyası** buradadır
(`zeus-war-defaults/src/main/resources/WEB-INF/`). İçerik: `com.zeus` bağımlılığı
(`slot="${zeus.module.slot}"` yer tutuculu) + subsystem dışlamaları (logging, weld,
batch-jberet, jsf, jaxrs) + `org.slf4j` dışlaması. Bu içerik Spring WAR'ının WildFly'da
açılması için zorunludur → sahibi platformdur, pipeline veya developer değil.

### 2) zeus-parent — üretim profili + slot property'si

- **`zeus.module.slot`** property'si (varsayılan: `main`). **Platform release başına
  sabitler; uygulama pom'unda override edilmez:**
  - Module içeriği (3. parti sürümler) değişen release → yeni versiyonlu slot değeri (örn. `1.1.0`).
  - İçerik değişmeyen release → önceki değer korunur (gereksiz 43 MB slot kopyası oluşmaz).
- **`zeus-generated-descriptor` profili** — `src/main/webapp` dizini olan projelerde
  (= WAR uygulamaları) otomatik aktifleşir; framework'ün jar/pom modüllerinde hiç devreye girmez.
  (`packaging=war` aktivasyonu Maven 4 özelliği; 3.9 POM modeli tanımıyor — denendi, parse hatası
  veriyor. Bu yüzden file-exists kullanılır.) **DİKKAT:** app'te `src/main/webapp` dizini var
  olmalı; descriptor üretildiği için dizin boş kalıyorsa **`.gitkeep` ile** var edilir (git boş
  dizini izlemez; dizin kaybolursa profil sessizce devre dışı kalır — aşağıdaki guard bunu yakalar):
  1. `dependency:unpack` → şablon `target/zeus-war-defaults/` altına açılır,
  2. `maven-war-plugin` `webResources` (filtering=true) → `${zeus.module.slot}` doldurulur,
     dosya WAR'ın `WEB-INF`'ine konur.
- **Üretilen dosya kazanır:** webResources, `src/main/webapp` kopyasından önce işlendiği
  için app'te unutulmuş/elle yazılmış eski bir descriptor varsa bile WAR'a **üretilen**
  girer (doğrulandı). App repo'larında bu dosya tutulmaz; `spring-wildfly-arch`'taki elle
  kopya silinmiştir.

Uygulamanın slot geçişi böylece **tek hamledir**: pom'da zeus-parent sürümünü yükselt →
build descriptor'ı yeni slot'la üretir → redeploy.

### 3) `install-zeus-module.sh --slot <ad>` — slot kurulumu

```bash
./scripts/install-zeus-module.sh                  # varsayılan: main (bugünkü davranış, mutable)
./scripts/install-zeus-module.sh --slot 1.1.0     # versiyonlu slot → modules/com/zeus/1.1.0/
FORCE=1 ./scripts/install-zeus-module.sh --slot 1.1.0   # bilinçli yeniden üretim (istisna)
```

- Versiyonlu slot'lar **IMMUTABLE**: var olan slot'un üzerine yazma reddedilir (exit 3).
  Her slot bir BOM release'inin donmuş kopyasıdır → çalışan sürüm her zaman izlenebilir.
- module.xml'de slot, `name` içine iki noktayla yazılır: `name="com.zeus:<slot>"`
  (**dikkat:** şema 1.6+'da ayrı `slot` attribute'u KALDIRILDI — `slot="..."` yazılırsa WildFly
  `XmlPullParserException: Unknown attribute "slot"` ile module'ü yükleyemez; pilotta yaşandı).
  main'de yalnız ad yazılır (varsayılan slot; bugünkü çıktı birebir korunur).
- `main` geriye uyumluluk için mutable bırakılmıştır (mevcut tek-slot akışı bozulmaz).

### 4) `verify-module-coverage.sh` — slot-farkındalıklı deploy guard

Script artık app'in hedef slot'unu kendisi çözer (`mvn help:evaluate
-Dexpression=zeus.module.slot`; üretilen descriptor'la aynı kaynak) ve:

1. **Descriptor-üretilmiş-mi:** build edilmiş WAR'da `jboss-deployment-structure.xml`
   (com.zeus bağımlılığı) yoksa durur — tipik neden `src/main/webapp` dizininin
   kaybolmasıdır (profil aktifleşmez); mesaj `.gitkeep` çözümünü söyler.
2. **Slot-kurulu-mu:** hedef slot sunucuda yoksa deploy kriptik açılış hatasıyla değil,
   erken ve çözümü söyleyen mesajla durur (`--slot X ile kur; restart gerekmez`).
3. **Kapsam:** runtime bağımlılık kapanışı `main`'e değil **app'in hedef slot'una** karşı denetlenir.

### 5) `slot-inventory.sh` — envanter + politika denetimi

"Hangi slot'lar kurulu, hangi app hangi slot'ta?" sorusunun ve CVE kapanış takibinin aracı.
Deploy edilmiş WAR'ların descriptor'ından slot'u okur; yedek dizinlerini (`*.bak-*`) saymaz.

```bash
./scripts/slot-inventory.sh                                   # varsayılan WILDFLY_HOME
WILDFLY_HOME=/path/staging-wildfly ./scripts/slot-inventory.sh
```

| Denetim | Sonuç |
|---------|-------|
| App, kurulu olmayan slot'a işaret ediyor | ❌ HATA, **exit 2** (çözüm komutu yazılır) |
| Kurulu slot sayısı > 2 (max-2 politikası) | ⚠ UYARI, **exit 1** |
| Kullanan app'i olmayan versiyonlu slot | ♻ silinebilir aday (bilgi; silme komutu + restart-penceresi uyarısı) |

Eski slot **ancak envanter "kullanan yok" dediğinde** silinir; exit kodları CI'da
politika kapısı olarak kullanılabilir.

### 6) `verify-staging.sh` — SLOT akışı (restart'sız staging doğrulaması)

```bash
# main akışı (değişmedi): yedekle → üret → RESTART → deploy → smoke → promote
WILDFLY_HOME=/path/staging ./scripts/verify-staging.sh <app-dir> [...]

# SLOT akışı: kur (immutable; kuruluysa mevcut içerikle doğrula) → RESTART YOK → deploy → smoke → promote
SLOT=1.1.0 WILDFLY_HOME=/path/staging ./scripts/verify-staging.sh <app-dir> [...]
```

SLOT akışında farklar:
- **Yedek alınmaz** (mevcut module'lere dokunulmaz), **restart edilmez** (sunucu ayaktaysa
  kesintisiz doğrulama; kapalıysa başlatılır).
- **Slot-hedef doğrulaması:** her app'in WAR'ındaki descriptor'ın gerçekten `$SLOT`'u
  gösterdiği kontrol edilir — yeni parent'la build edilmemiş app, eski slot'la yeşil görünüp
  gate'i yanıltamaz (gate kırmızı düşer, mesaj parent yükseltmeyi söyler).
- Önkoşul: zeus-parent'ta `zeus.module.slot=$SLOT` set edilip parent yayınlanmış olmalı.
- Promote artifact'ı slot dizininden üretilir (`com-zeus-module-<slot>-<stamp>.tar.gz`);
  prod talimatı restart'sızdır (tar aç → app'ler parent bump + redeploy → envanterle kapanış).

### 7) `generate-war-excludes.sh` — üretilen WAR dışlama listesi

Descriptor gibi, WAR'ın dışlama listesi de **üretilir**. Kaynağı `zeus-wildfly-module`
(SOAP için ek olarak `zeus-soap-wildfly-module`) runtime kapanışıdır; çıktısı
`zeus-parent` ve `zeus-soap-parent` POM'larındaki marker bloklarıdır.
`install-zeus-module.sh` kendi sonunda bunu çağırır — module ve liste aynı kapanıştan
üretildiği için ayrışamazlar. CI için: `--check`.
Gerekçe: `19-war-paketleme-module-farkindaligi.md`.

## ~~Uygulamaya özel ek module bağımlılığı~~ (`zeus.descriptor.extra.modules`) — **KALDIRILDI**

> **Bu mekanizma 2026-09-11'de framework'ten TAMAMEN SİLİNDİ** (commit `1b08cb8`).
> `zeus.descriptor.extra.modules` property'si `zeus-parent`'ta yoktur, descriptor
> şablonlarındaki yer tutucu kaldırılmıştır. **Bir uygulamanın pom'unda bu property hâlâ
> yazılıysa hiçbir şey yapmaz — sessizce etkisizdir.** Bölüm, hem tarihsel kayıt hem de
> içindeki teşhis bilgisi hâlâ geçerli olduğu için silinmedi.

**Neden kaldırıldı.** Descriptor içeriği **TİP kararıdır, uygulama kararı değil.** Uygulamanın
kendi pom'undan paylaşımlı bir descriptor'a module enjekte etmesi bu ilkeyi deliyordu: iki
uygulama aynı tipte olup farklı classloader görünürlüğüne sahip olabiliyordu ve hangi
uygulamanın hangi module'ü gördüğü tek yerden okunamıyordu. Aynı gerekçeyle CXF için var olan
opt-in de silindi (`19-war-paketleme-module-farkindaligi.md`, `20-zeus-sms.md`).

**Çözdüğü sorun ortadan kalkmadı — hâlâ geçerli ve hâlâ çözümsüz.** İnce WAR'da Oracle sürücü
sınıfları deployment classloader'ında **kasten yoktur** (`WEB-INF/lib`'den `packagingExcludes`
atar, `com.zeus` module'ünden `EXCLUDE_REGEX` atar — `08-wildfly-module-dagitim.md`). Uygulama
JNDI'dan `DataSource` aldığı sürece buna ihtiyacı da olmaz. Ama **devralınan bir util katmanı**
sürücü sınıflarına *kod olarak* bağlıysa — `Class.forName("oracle.jdbc.OracleDriver")`,
`Connection`'ı `OracleConnection`'a unwrap, `OracleTypes.CURSOR` — deploy şu hatayla düşer:

```
could not load JDBC driver class / ClassNotFoundException: oracle.jdbc.OracleDriver
```

**Bu hatanın teşhis imzası (DEĞİŞMEDİ, saklanmaya değer):** `jboss-cli`'de
`test-connection-in-pool` **yeşildir**. Sunucu sürücüyü yükleyebiliyordur; yükleyemeyen
deployment'tır. Datasource testinin geçmesi, sorunu sunucu tarafında aramaktan vazgeçmek için
yeterli sebeptir.

**Bugün doğru çözüm iki tanedir; ikisi de henüz uygulanmadı (2026-09-11, ertelendi):**

1. **Kalıcı düzeltme** — util'in sürücü sınıflarına bağımlılığını kaldırmak: JNDI `DataSource`,
   `OracleTypes.CURSOR` yerine `java.sql.Types.REF_CURSOR`, `OracleConnection` unwrap'lerini
   standart JDBC API'sine çevirmek. Hedef budur.
2. **Yeni uygulama TİPİ** — `com.oracle.ojdbc`'yi import eden kendi descriptor'ı olan bir tip:
   `zeus-oracle-parent/pom.xml` (yalnız `zeus.descriptor.dir` farklı) +
   `zeus-war-defaults/src/main/resources/descriptor-oracle/` + kök `<modules>`. Uygulama
   tarafında değişen tek şey `<parent>` satırıdır; uygulama yine hiçbir module adı yazmaz,
   yani ilke korunur. Bedeli: tip kalıcıdır ve teknik borcu mimariye yazar.

**Neden module import jar kopyalamaktan farklı ve güvenli (her iki seçenekte de geçerli).**
`com.oracle.ojdbc`, JCA katmanının datasource için kullandığı module'ün **ta kendisidir**;
import edilince sınıf kimliği tek kalır, JNDI'dan gelen `Connection` ile uygulamanın gördüğü
tip aynı classloader'dandır. Yasak olan, sürücünün **ikinci bir kopyasını** (WAR'a veya
`com.zeus`'a jar olarak) koymaktır — o durumda tipler ayrışır ve
`ClassCastException`/`LinkageError` çıkar. **module import ≠ jar kopyası.**

**Fat WAR tiplerinde konu zaten yoktu.** `descriptor-bff` ve `descriptor-standalone`
şablonlarında `<dependencies>` bloğu hiç yoktur; sürücü zaten `WEB-INF/lib`'dedir ve module
import etmek tam da kaçınılan çift kopyayı yaratırdı. O tiplerde çözüm sürücüyü `provided`
bildirmektir — `14-uygulama-tipi-parentlar.md` → "Standalone + `zeus-database`".

**Tarihsel not (2026-09-08, mekanizma yaşarken doğrulanmıştı):** boş property → descriptor
eskisiyle aynı; dolu property → `<module name="com.oracle.ojdbc"/>` descriptor'a giriyor ve
`WEB-INF/lib`'de ojdbc jar sayısı **0** kalıyordu. O doğrulama artık silinmiş bir koda aittir.

## CVE rollout'u slot'larla (hedef akış)

```
1. BOM'da override + zeus release (örn. 1.1.0); zeus-parent'ta zeus.module.slot=1.1.0
2. Staging WildFly'da doğrula (verify-staging.sh hattı)
3. Prod'a slot kur:  ./scripts/install-zeus-module.sh --slot 1.1.0   ← RESTART YOK
4. Her app hazır olunca: pom'da parent sürümünü 1.1.0'a yükselt + deploy.sh  (saniyeler)
5. Sorun çıkarsa: parent sürümünü geri al + redeploy                  ← app bazında rollback
6. Envanter "tüm app'ler 1.1.0'da" deyince eski slot silinir, CVE kapatılır
```

## Politikalar (mimarinin erimemesi için ŞART)

- **Max 2 aktif slot:** slot'lar kalıcı parçalanma değil, **geçiş dönemi** mekanizmasıdır.
  Hedef durum her zaman tek slot; geçiş bitince eski slot silinir. Aksi halde N app × M sürüm
  parçalanması tek-module mimarisinin bütün faydasını eritir.
- **Eski slot'u silme zamanlaması:** yüklü bir module'ün jar'ları tembel açılır — **hâlâ o
  slot'u kullanan app varken dizin silinmez** (çalışır görünür, sonra sınıf yüklerken patlar).
  Tüm app'lerin geçtiği doğrulandıktan sonra, ideali bir sonraki restart penceresinde silinir.
- **Bellek/disk:** kullanılan her slot kendi classloader'ı ile yüklenir (jar'lar bellekte
  slot başına bir kez) + slot başına ~43 MB disk. Max-2 politikasının nedeni budur.
- **"Tek yama → herkes kapsanır" telafisi:** CVE kapanışı artık app'lerin parent bump'ına
  bağlıdır → platform "hangi app hangi slot'ta" envanterini izler (deploy edilmiş WAR'ların
  descriptor'ından okunabilir) ve eski slot'a son-kullanma SLA'sı uygular.

## global-modules kullanılan ortamlara geçiş

`standalone.xml` `<global-modules>` ile bağlanan ortamlarda slot seçimi yapısal olarak
imkansızdır (değer sunucu başına tektir). Geçiş sırası — **iki mekanizma aynı anda farklı
slot gösterirse çift sınıf / LinkageError riski olduğundan** sıra önemlidir:

1. Tüm app'ler üretilmiş descriptor + `slot=main` ile deploy edilir (davranış birebir aynı).
2. Tek bakım penceresinde `<global-modules>`'tan `com.zeus` çıkarılır + reload.
3. Slot mekanizması kullanılabilir; bundan sonrası restart'sız.

## ⚠️ DOĞRULANMAMIŞ: versiyonlu slot yolu hiç çalıştırılmadı

**Bugüne kadar yapılan tüm kurulum, deploy ve doğrulama `main` slot'u üzerinde koştu.**
`--slot <ad>` / `--base-slot <ad>` dalları bir kez bile yürütülmedi. Bu, mekanizmanın
yanlış olduğu anlamına gelmez — hiç sınanmadığı anlamına gelir. Son durum (2026-09-10):

| Yol | Durum |
|---|---|
| `install-zeus-module.sh` (varsayılan, `main`) | ✅ defalarca koştu |
| `install-zeus-module.sh --slot 1.1.0` | ❌ hiç koşmadı |
| `install-zeus-module.sh --module soap --base-slot 1.1.0` | ❌ hiç koşmadı |
| Versiyonlu slot'a bağlanan bir uygulamanın deploy'u | ❌ hiç koşmadı |

**İlk versiyonlu slot kurulumunda özellikle sınanacaklar:**

1. **`module.xml`'deki sözdizimi.** `com.zeus.soap` üretilirken versiyonlu dal
   `<module name="com.zeus:1.1.0"/>` yazıyor (`scripts/install-zeus-module.sh:181-184`);
   klasik biçim `<module name="com.zeus" slot="1.1.0"/>`. Bu dal hiç çalışmadığı için
   JBoss Modules'ın onu çözdüğü doğrulanmadı. **İlk sınanacak şey budur.**
2. **Descriptor ↔ slot uyumu.** Uygulamanın `zeus.module.slot`'u ile sunucuda kurulu
   slot'un eşleştiği; `verify-module-coverage.sh`'ın slot-kurulu kontrolünün versiyonlu
   adla da çalıştığı.
3. **Üretilen dışlama listeleri slot'tan habersizdir.** Liste her zaman çalışma ağacındaki
   `zeus-wildfly-module` kapanışından üretilir; uygulamanın hedeflediği slot'la ilişkisi
   yoktur. `main` dışında bir slot hedeflenirken listenin o slot'un içeriğiyle uyumlu
   olduğunu `verify-module-coverage.sh`'ın ters kapsam kontrolü denetler — ama bu da
   yalnız `main` üzerinde sınandı.

Bu not, mekanizma gerçekten bir versiyonlu slot'la çalıştırılıp doğrulanana kadar kalır.

## Doğrulananlar (bu geliştirmede uçtan uca test edildi)

- zeus-fw build: profil framework'ün jar modüllerinde aktifleşmiyor (döngü yok).
- App WAR'ı: descriptor üretiliyor, `slot="main"` doluyor; app'te elle kopya varken bile
  üretilen kazanıyor; elle kopya silindi (webapp'ta yalnız `.gitkeep`), build yine doğru üretiyor.
- `--slot 9.9.9-test` sahte WILDFLY_HOME'a kuruldu: module.xml'de `slot` attribute'u doğru;
  ikinci kurulum immutability kilidine takıldı (exit 3); `FORCE=1` kaçışı mevcut.
- Gerçek WildFly'a deploy: coverage guard `com.zeus:main`'i çözdü, deploy + smoke test (8/8) geçti.
- Negatif test: `src/main/webapp` kaldırılarak build edildi → WAR descriptor'sız çıktı →
  guard deploy'dan önce net mesajla durdurdu (exit 2); dizin geri konunca akış düzeldi.
- `packaging=war` aktivasyonu denendi: Maven 3.9.11 POM modeli `<activation><packaging>`
  tag'ini tanımıyor (Maven 4 özelliği) → file-exists aktivasyonunda kalındı.
- **Pilot prova (slot 1.0.1-rc1, gerçek WildFly, uçtan uca):**
  - Negatif: parent hâlâ `main` üretirken `SLOT=1.0.1-rc1 verify-staging.sh` → slot kuruldu,
    restart olmadı, deploy geçti ama **slot-hedef doğrulaması gate'i kırdı** (beklenen davranış).
  - module.xml'de `slot` attribute'unun şema 1.9'da reddedildiği görüldü → `name="com.zeus:<slot>"`
    biçimine geçildi (yükleme hatası cache'lenmedi; düzeltme restart'sız devreye girdi).
  - Pozitif: parent `zeus.module.slot=1.0.1-rc1` ile yayınlandı → gate yeşil (deploy + smoke 9/9,
    **sunucu hiç restart edilmeden** app main→1.0.1-rc1 geçti), slot'lu promote artifact üretildi.
  - Envanter geçiş sırasında 2 slot / app rc1'de gösterdi; parent main'e geri alınıp app redeploy
    edilince (app bazında rollback provası) rc1 "silinebilir aday" oldu → silindi → envanter temiz.

## İlgili

- Module üretimi/governance: `08-wildfly-module-dagitim.md`
- CVE süreci: `09-cve-guvenlik-yamalama.md` (§7c'deki "geçici versiyonlu module" istisnası
  artık bu dokümanla sistematik mekanizmadır)
- Karar analizi sunumu: `sunum/com-zeus-module-surumleme-stratejisi.pptx`