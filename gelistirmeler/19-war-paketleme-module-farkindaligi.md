# 19 — WAR Paketlemesi: allowlist'ten module-farkındalığına (tasarım)

**Durum:** UYGULANDI (2026-09-08). Tasarım kararları ve ölçümler aşağıda; uygulama planı
`docs/superpowers/plans/2026-09-08-war-paketleme-module-farkindaligi.md`.

İnce WAR'ın hangi jar'ları taşıyacağı bugün **yanlış tarafta** tanımlı. Kural
"module'ün verdiğini at" olması gerekirken "zeus- dışındakini at" biçiminde yazılmış.
Bu doküman kusuru, kararları ve hedef tasarımı kayıt altına alır.

## Kusur

`zeus-parent/pom.xml`:

```xml
<zeus.war.packaging-excludes>%regex[WEB-INF/lib/(?!(zeus-${zeus.war.keep})).*\.jar]</zeus.war.packaging-excludes>
```

Bu bir **allowlist**: adı `zeus-` ile başlamayan **her** jar atılır. Ölçüldü —
`spring-wildfly-arch`'ın WAR'ında `WEB-INF/lib` tam olarak beş dosya:
`zeus-base`, `zeus-service`, `zeus-ai`, `zeus-database`, `zeus-logger`.

Olması gereken **denylist**: paylaşımlı module'ün sağladığı jar'lar atılır, **geri kalan
her şey WAR'da taşınır**.

**Neden önemli:** `com.zeus` module'ü 157 jar içerir; bir uygulamanın runtime kapanışı
bundan geniş olabilir. Bugünkü kuralda o fazlalık **sessizce silinir** ve WildFly'da
`NoClassDefFoundError` olarak geri döner — genelde deploy anında, kriptik bir hatayla
(`08-wildfly-module-dagitim.md`). Tek kaçış yolu `zeus.war.keep` ile elle jar öneki
listesi yazmaktır; bugün hiçbir uygulama kullanmıyor.

Yani eksik bir özellik değil, **varsayılanın yanlış yönde olması** söz konusu. Güvenli
varsayılan "bilmediğimi at" değil, "bildiğimi at, kalanı taşı" olmalıydı.

## Reddedilen yaklaşımlar (ölçümle)

Tasarım iki deneyle daraltıldı; ikisi de negatif çıktı ve kaydı burada tutulur ki
ileride tekrar denenmesin.

**1) `provided` scope toplayıcısı — ÇALIŞMIYOR.** Uygulamaya `zeus-wildfly-module`
`type=pom, scope=provided` olarak eklenirse Maven tüm module kapanışını `provided`
işaretler ve WAR'dan doğal olarak düşer diye denendi.

| | `WEB-INF/lib` jar sayısı |
|---|---|
| Baseline (`packagingExcludes` boş) | 170 |
| + `zeus-wildfly-module` provided/pom | 170 |

Hiç fark yok. Sebep: aynı artefaktlar `zeus-base`/`zeus-database` üzerinden de derinlik
2'de `compile` olarak gelir; Maven'ın "en yakın kazanır" mediasyonunda `provided`
işareti tutmaz. **Saf Maven scope hilesiyle çözülemez.**

**2) `properties-maven-plugin` ile runtime override — ÇALIŞMIYOR.** Liste bir properties
dosyasından okunup `zeus.war.packaging-excludes`'a set edilsin, `maven-war-plugin` onu
görsün diye denendi. Sonuç: WAR'da yine yalnız 5 zeus jar'ı — war-plugin POM'daki
orijinal değeri kullandı. Maven `${...}` ifadesini efektif model kurulurken çözer;
plugin'in sonradan set ettiği değer ona ulaşmaz.

Bu ikinci sonuç, listenin **runtime'da enjekte edilemeyeceğini**, dolayısıyla POM'a
**yazılması** gerektiğini söyler.

## Hedef tasarım (B′)

### 1) Kural tersine döner — iki üretilmiş liste

`zeus-parent/pom.xml` ve `zeus-soap-parent/pom.xml`, property'yi üretilmiş bir denylist
olarak taşır:

```xml
<!-- ÜRETİLMİŞTİR — elle düzenlenmez. Üretici: scripts/generate-war-excludes.sh -->
<zeus.war.packaging-excludes>%regex[WEB-INF/lib/(spring-core|spring-beans|jackson-databind|…)-.*\.jar]</zeus.war.packaging-excludes>
```

| Tip | Parent | Atılan küme | WAR'da kalan |
|---|---|---|---|
| standard | `zeus-parent` | `com.zeus` (157 artifactId) | `zeus-*` + hiçbir module'de olmayanlar |
| soap | `zeus-soap-parent` | `com.zeus` ∪ `com.zeus.soap` (157 + 23) | `zeus-*` + hiçbir module'de olmayanlar |
| bff | `zeus-bff-parent` | — (boş; fat WAR) | her şey |
| standalone | `zeus-standalone-parent` | — (boş; fat WAR) | her şey |

**SOAP tipi ayrı liste ZORUNLU.** `zeus-soap-parent` bu property'yi bugün override
etmiyor, `zeus-parent`'tan miras alıyor. Allowlist'te bu zararsızdı (zaten her şey
atılıyordu); denylist'e çevrilince tek liste kullanılırsa CXF yığını SOAP WAR'ına girer
ve `com.zeus.soap` module'üyle **çift kopya** oluşur → `LinkageError`. Kural:
**`com.zeus` veya `com.zeus.soap` içindeki hiçbir bağımlılık WAR'a konmaz.**

Eşleştirme **artifactId bazındadır**, sürüm dahil değildir: aynı artifactId module'deyse
sürüm farklı olsa bile WAR'a girmez. Böylece runtime'da tek kopya kalır ve module'ün
sürümü geçerli olur (lockstep korunur). Sürüm çakışmasının alternatifi — app'in kendi
sürümünü taşıması — classpath'te iki kopya bırakır ve hangisinin yükleneceği
classloader sırasına kalırdı; bilinçle reddedildi.

`zeus-*` jar'ları listede **yer almaz**, çünkü `install-zeus-module.sh` onları zaten
module'e koymaz (`EXCLUDE_REGEX`). Dolayısıyla WAR'da kalmaları için özel bir kural
gerekmez — doğal sonuçtur. Aynı şey `ojdbc*`/`orai18n`/`ucp*` ve `jakarta.*-api` için de
geçerlidir: module'de olmadıkları için "module'de ne varsa" kuralına göre listeye
girmezlerdi. Bu istenmez — aşağıdaki "Module dışı sabit kuyruk" bölümü onları listeye
açıkça ekler.

### 2) Üretici: `scripts/generate-war-excludes.sh`

`zeus-wildfly-module`'ün (soap listesi için ek olarak `zeus-soap-wildfly-module`'ün)
runtime bağımlılık kapanışını `dependency:list -DincludeScope=runtime` ile çözer,
artifactId kümesini çıkarır, iki parent POM'daki property bloğunu yeniden yazar.

Kapanış çözümü `install-zeus-module.sh`'ın module'ü üretirken kullandığı **kaynağın
aynısıdır**; dışlama kümesi de aynı olmalıdır (`zeus-*`, `jakarta.*-api`, `lombok`,
`ojdbc*`/`orai18n`/`ucp*`) — yani "module'de fiilen ne varsa liste odur".

**Drift'i imkânsız kılan bağlantı:** `install-zeus-module.sh` kendi sonunda bu üreticiyi
çağırır. Module ve liste aynı komuttan, aynı kapanıştan üretilir; ayrı bir senkron
denetimi gerekmez. Üretici tek başına da çalıştırılabilir (`--check` ile yalnız fark
raporlar, CI için).

### 3) Kaldırılanlar

- **`zeus.war.keep`** — varlık sebebi tam olarak bu boşluktu. Hiçbir uygulama
  kullanmıyor; property, kullanımları ve dokümantasyonu silinir.
- **`verify-module-coverage.sh`'ın "eksik bağımlılık" dalı** — artık **yanlış pozitif**
  üretir: eksik olan jar WAR'da taşınacağı için deploy'u durdurmak yanlıştır. Bu dal ve
  `EXCLUDE_REGEX`/`KEEP` mantığı silinir. **Slot-kurulu-mu** ve **üretilmiş-descriptor**
  kontrolleri kalır; ikisi de hâlâ gerçek hataları yakalar.

### 4) Bilinçli kabul edilen taviz

Module'de olmayan bağımlılık **sessizce** WAR'a girer; ne build ne deploy uyarı basar.
Bedeli: `zeus-wildfly-module` zamanla uygulamaların gerisine düşebilir ve kimse fark
etmez — paylaşımlı module'ün "tüm uygulamaların birleşimi" iddiası zayıflar
(`08-wildfly-module-dagitim.md`). Karşılığında deploy hiç kırılmaz ve sıfır ek makine
kurulur.

Bu bir gözden kaçma değil, **maliyet gerekçeli bir karardır**. Yeniden açılma
tetikleyicisi: WAR boyutlarının belirgin büyümesi ya da aynı 3. parti kütüphanenin
birden çok uygulamada WAR'da taşındığının fark edilmesi. O noktada build zamanı uyarısı
(antrun) yeniden değerlendirilir.

## Module dışı sabit kuyruk (üretilen listeye her zaman eklenir)

Denylist "module'de ne varsa o" kuralıyla üretilir, ama module'de **bilerek olmayan** ve
yine de WAR'a girmemesi gereken bir küme vardır. Bunlar üretilen listenin sonuna sabit
olarak eklenir:

| Kalıp | Neden WAR'a girmemeli |
|---|---|
| `ojdbc[0-9]+`, `orai18n`, `ucp[0-9]+` | WildFly'ın kendi `com.oracle.ojdbc` module'ünden gelir; datasource ona bağlıdır. WAR'da ikinci kopya olursa JNDI'dan gelen `Connection` ile uygulamanın gördüğü tip ayrışır → `ClassCastException` (`08-wildfly-module-dagitim.md`). |
| `jakarta.*-api` | WildFly server module'lerinden gelir. WAR'daki kopya konteynerin API'siyle çakışır → `LinkageError`. |
| `lombok`, `spring-boot-jarmode-*` | Runtime'da işlevsiz; WAR'ı şişirir. |

**Kural tek cümlede:** sabit kuyruk, `install-zeus-module.sh`'ın `EXCLUDE_REGEX`'inden
`zeus-*` çıkarılmış hâlidir. O regex zaten "module'e girmez" diyen kümedir; `zeus-*`
dışındaki her üyesi aynı zamanda "WAR'a da girmez" demektir. Tek fark `zeus-*`'dır:
module'e girmez **ama** WAR'da taşınır.

Bu, `ojdbc` açısından bugünkü davranışın **korunması** demektir: allowlist onu zaten
atıyordu, denylist de atmaya devam edecek. Sabit kuyruk olmasaydı ojdbc WAR'a girer ve
sürücü tekliği bozulurdu — bu tasarımın en kolay gözden kaçacak ayrıntısıdır.

## Kalıntı risk: artifactId eşleşmesi groupId'ye bakmaz

Eşleşme artifactId bazlıdır, groupId'ye bakmaz. Dolayısıyla farklı bir groupId'den gelen
aynı adlı bir artefakt (listede `annotations`, `okio`, `ST4`, `itu` gibi genel adlar var)
WAR'dan atılır ama module başka bir üreticinin sınıflarını sağlar → `NoClassDefFoundError`.
Bu, "sürümden bağımsız artifactId eşleşmesi" kararının kabul edilmiş bedelidir; bugün
bilinen bir örneği yoktur, ama yeni bir bağımlılık eklenirken akılda tutulmalıdır.

## Doğrulama planı

| Kapı | Beklenen | Sonuç |
|---|---|---|
| `spring-wildfly-arch` WAR'ının jar kümesi | **Değişmemeli** — kapanışı `com.zeus` tarafından tam karşılanıyor (`verify-module-coverage.sh` ✅ ile ölçüldü). Regresyon yok kanıtı. | ✅ 5 jar (`zeus-base`, `zeus-service`, `zeus-ai`, `zeus-database`, `zeus-logger`) — allowlist dönemindeki sayıyla birebir aynı |
| `spring-wildfly-arch` WildFly deploy + smoke | Yeşil | ✅ WildFly 41.0.0.Final'a gerçek deploy: `WFLYSRV0016: Replaced deployment`, uygulama `/spring-wildfly-arch` context'i altında yanıt verdi (`GET /api/products` → 200), tek seferlik elle yapılmış log denetiminde `ERROR`/`NoClassDefFoundError`/`LinkageError`/`ClassCastException` **sıfır** (scripted test değildir). Fonksiyonel smoke (`http://127.0.0.1:8080` — `localhost` bu makinede Docker'ın IPv6 dinleyicisine düşüyor, `17-module-yenileme-runbook.md`'deki bilinen tuzak): Oracle stored procedure'lerinden 5 gerçek kayıt; `GET /v3/api-docs` → **200**. |
| SOAP örnek uygulaması WAR'ı | Hiçbir CXF jar'ı içermemeli | ✅ `zeus-sample-soap` WAR'ında `cxf`/`wsdl4j` eşleşmesi **0** (8 jar toplam, hepsi `zeus-*`) |
| ojdbc | WAR'da **0** kopya | ✅ `spring-wildfly-arch` WAR'ında `WEB-INF/lib/ojdbc*` **0** |
| `test-project--service` | Bugün silinen jar'lar WAR'a girmeli, deploy geçmeli | **doğrulanmadı — bu ortamda erişilemiyor.** Uygulama Windows geliştirme makinesinde (`D:/dvl_ij/...`); bu workspace'te yok. |
| bff / standalone WAR'ları | Değişmemeli (property boş kalır) | ✅ `zeus-bff-parent`/`zeus-standalone-parent`'ta `<zeus.war.packaging-excludes/>` hâlâ boş; `zeus-sample-bff` 52 jar, `zeus-sample-standalone` 40 jar (fat WAR — dışlama yok) |

Ayrıca: altı test (`test-generate-war-excludes.sh`, `test-war-packaging.sh`,
`test-war-packaging-soap.sh`, `test-no-war-keep.sh`, `test-coverage-guard.sh`,
`test-generator-wiring.sh`) + `./scripts/generate-war-excludes.sh --check` + framework
`mvn clean install` (testler dahil, `-DskipTests` yok) hepsi ✅.

## İlgili

- `08-wildfly-module-dagitim.md` — module üretimi, `EXCLUDE_REGEX`, sürücü kuralı
- `10-versiyonlu-slot-uretilen-descriptor.md` — üretilen descriptor, slot lockstep
- `14-uygulama-tipi-parentlar.md` — tip parent'ları, fat WAR tipleri
- `17-module-yenileme-runbook.md` — module yenileme akışı (üretici buraya bağlanır)
