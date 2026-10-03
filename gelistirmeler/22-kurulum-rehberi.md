# 22 — Kurulum Rehberi (sıfırdan platform + tüketen uygulama)

Bu doküman iki soruyu cevaplar:

1. **Repoyu yeni klonladım — hangi script'i çalıştıracağım?**
2. **Bu framework'ü kullanacak uygulama ne yapmak zorunda?**

Sonunda, benzeri bir framework kuracaklar için taşıyıcı kurallar var (§5).

Kısa cevap: platform tarafında çalıştıracağınız **tek** script `install-zeus-module.sh`'tır.
Dışlama listesini üreten `generate-war-excludes.sh --write`'ı kendi sonunda o çağırır
(`scripts/install-zeus-module.sh:369`) — elle ikinci bir şey koşmanız gerekmez.

---

## 1. Platform tarafı — sıfırdan

### Ön koşullar

| Ne | Not |
|---|---|
| JDK **25** | `mvn` çağrılarından önce `JAVA_HOME` bunu göstermeli |
| Maven 3.9+ | |
| WildFly **41** | Kurulu ve yolu biliniyor olmalı |

`jandex`'i **elle kurmanıza gerek yoktur**: `install-zeus-module.sh` gerekli sürümü
(`io.smallrye:jandex:3.2.0`) ilk koşuda `mvn dependency:get` ile `~/.m2`'ye indirir
(`install-zeus-module.sh:184-199`). Yalnız ilk kurulumda Maven deposuna erişim gerekir;
erişilemezse script açık bir hatayla durur, sessizce indekssiz module üretmez.

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
export WILDFLY_HOME=/path/to/wildfly-41.0.0.Final
```

`WILDFLY_HOME` verilmezse script'teki varsayılan kullanılır (`install-zeus-module.sh:43`).
Staging/prod ayrımı **yalnız** bu değişkenle yapılır — script'in içinde hedef sunucu yazılı değildir.

### Adımlar

```bash
cd zeus-fw

# 1) Artefaktları ~/.m2/repository/com/zeus/ altına kur
mvn clean install

# 2) Paylaşımlı com.zeus module'ünü üret + WAR dışlama listesini yeniden yaz
./scripts/install-zeus-module.sh

# 3) SOAP tipi uygulama çalıştıracaksanız (yoksa atlayın)
./scripts/install-zeus-module.sh --module soap
```

> **2. adımdan sonra WildFly çalışıyorsa RESTART şarttır.** `main` slot'unun module tanımı
> cache'lidir. *Yeni bir versiyonlu slot* eklemek (`--slot 1.1.0`) restart gerektirmez —
> versiyonlu slot'ların varlık sebebi budur (bkz. `10-versiyonlu-slot-uretilen-descriptor.md`).

### `install-zeus-module.sh` seçenekleri

```bash
./scripts/install-zeus-module.sh                          # com.zeus, slot: main
./scripts/install-zeus-module.sh --slot 1.1.0             # versiyonlu slot (IMMUTABLE)
./scripts/install-zeus-module.sh --module soap            # com.zeus.soap (CXF yığını)
./scripts/install-zeus-module.sh --module soap --base-slot 1.1.0
WILDFLY_HOME=/path/staging ./scripts/install-zeus-module.sh
```

Versiyonlu slot'lar bir kez kurulur, **üzerine yazılmaz** (script reddeder; bilinçli yeniden
üretim için `FORCE=1`). Her slot bir BOM release'inin donmuş kopyasıdır.

### Doğrulama

```bash
./scripts/run-guards.sh              # tam ortam: 15 guard
./scripts/run-guards.sh --fw-only    # yalnız repoya yeten guard'lar (kurulu module/app gerekmez)
./scripts/run-guards.sh --list       # ne koşacağını göster, hiçbir şey çalıştırma
./scripts/run-guards.sh --require-all # atlama = HATA (CI / tam ortam)
```

Guard etiketleri neye ihtiyaç duyduklarını söyler:

| Etiket | Gereksinim |
|---|---|
| `[fw]` | Yalnız bu repo |
| `[wf]` | Kurulu `com.zeus` module'ü |
| `[app-*]` | Kardeş uygulama repoları (+ bazıları sunucu) |

**Yeşil değilse durun.** Guard'lar ölçüm başarısızlığında da kırmızıya döner — sessiz yeşil
yoktur, bilerek öyle tasarlandılar.

### Deploy öncesi kapı (uygulama başına)

```bash
./scripts/verify-module-coverage.sh /path/to/uygulama
```

Uygulamanın hedeflediği slot kurulu mu, üretilmiş descriptor WAR'da yerinde mi, dışlanan her
artifactId module'de mevcut mu — bunları deploy'dan **önce** söyler. WAR build edilmemişse
ilgili kontroller `ATLANDI` diye raporlanır, sessizce geçilmez.

---

## 2. Uygulama tarafı — tüketen her proje

Yedi madde. İlk beşi eksikse deploy kriptik bir hatayla düşer.

| # | Zorunluluk | Eksikse ne olur |
|---|---|---|
| 1 | `<parent>` = `com.zeus:zeus-parent` (`<relativePath/>` boş) | Sürüm/plugin/paketleme yönetimi hiç gelmez |
| 2 | `<packaging>war</packaging>` | |
| 3 | Bağımlılıklara **sürüm yazmamak** | Module'dekiyle ayrışır → `LinkageError` |
| 4 | Ana sınıf `extends ZeusServletInitializer` | Loglama/correlation varsayılanları gelmez (§5'teki classloader kısıtı) |
| 5 | `src/main/webapp/.gitkeep` | Descriptor üreten profil aktifleşmez; WAR `com.zeus`'u göremez |
| 6 | Kullanılan yetenekler için `zeus.<yetenek>.enabled=true` | Açılışta net bir hata (bkz. `21-yetenek-opt-in.md`) |
| 7 | `jboss-deployment-structure.xml` **yazmamak** | Elle yazılan dosya tipin sözleşmesini bozar |

### pom.xml

```xml
<parent>
    <groupId>com.zeus</groupId>
    <artifactId>zeus-parent</artifactId>
    <version>2.0.0-SNAPSHOT</version>
    <relativePath/>
</parent>

<packaging>war</packaging>

<dependencies>
    <!-- Sürüm YAZILMAZ — zeus BOM yönetir -->
    <dependency>
        <groupId>com.zeus</groupId>
        <artifactId>zeus-base</artifactId>
    </dependency>
    <dependency>
        <groupId>com.zeus</groupId>
        <artifactId>zeus-database</artifactId>
    </dependency>
</dependencies>
```

### Ana sınıf

```java
public class UygulamaApplication extends ZeusServletInitializer {
    public static void main(String[] args) {
        SpringApplication.run(UygulamaApplication.class, args);
    }
}
```

### application.properties

```properties
# Yalnız KULLANDIKLARINIZI yazarsınız; kullanmadığınız için hiçbir şey yazmazsınız.
zeus.database.enabled=true
zeus.ai.enabled=true
```

> **Tuzak:** `src/test/resources/application.properties` varsa `src/main`'dekini **gölgeler**.
> Yetenek bildirimlerini orada da tekrarlayın, yoksa `mvn package` düşer.

### Tip seçimi

| İhtiyaç | Parent | Descriptor |
|---|---|---|
| REST (standart) | `zeus-parent` | `descriptor-standard` |
| SOAP **endpoint yayınlıyor** | `zeus-soap-parent` | `descriptor-soap` |
| Gateway / React sunumu (FAT WAR) | `zeus-bff-parent` | `descriptor-bff` |
| Classloader izolasyonu (self-contained) | `zeus-standalone-parent` | `descriptor-standalone` |

SOAP **istemcisi** olmak standart tipte kalmanızı engellemez — `zeus-sms` bunun örneğidir.
Tipi değiştiren şey `@WebService` **yayınlamaktır**.

---

## 3. Sonradan ne değişince ne koşulur

| Değişen | Yapılacak | Restart? |
|---|---|---|
| Yalnız zeus **Java kodu** | `mvn clean install` | Hayır — zeus jar'ları WAR'da taşınır |
| **3. parti** bağımlılık / BOM / CVE override | `mvn clean install` → `install-zeus-module.sh` | **Evet** (main slot) |
| Aynısı, kesintisiz istiyorsanız | `--slot <yeni>` + uygulamaları sırayla geçirin | Hayır |
| Yeni yetenek modülü | Üsttekiler + `ZeusCapabilities`'e kayıt | Evet |

Son satır önemli: yeni bir yetenek module'e girip `ZeusCapabilities`'e kaydedilmezse
`test-autoconfig-sahipligi.sh` **kırmızıya döner**. Bu bilinçlidir — kaydedilmemiş bir
autoconfig, onu istemeyen her uygulamada sessizce çalışırdı.

---

## 4. İlk kurulumda en sık görülen dört hata

| Belirti | Sebep |
|---|---|
| `could not load JDBC driver class`, ama `test-connection-in-pool` **yeşil** | Sunucu sürücüyü yükleyebiliyor; yükleyemeyen deployment. Bkz. `10-...md` |
| WAR açılıyor ama `DispatcherServlet` yok / her istek 404 | `src/main/webapp/` yok → descriptor üretilmedi |
| `Zeus yetenek bildirimi eksik: ...` | Modülü pom'a yazıp property'yi yazmamışsınız (§2.6) |
| `NoClassDefFoundError` (module yenilendikten sonra) | `main` slot güncellendi, WildFly restart edilmedi |

---

## 5. Benzeri bir framework kuracaklar için — taşıyıcı kurallar

Bu mimarinin ayakta durmasını sağlayan şey modül listesi değil, şu dört kuraldır.

**1. Dışlama listesi ÜRETİLİR, elle yazılmaz.** Module'ün içeriği ve WAR'ın dışlama listesi
**aynı bağımlılık kapanışından** çıkmalıdır. İki ayrı kaynaktan beslenirse er geç ayrışırlar ve
bir jar hem module'de hem WAR'da bulunur → `LinkageError` / `ClassCastException`. Bu ayrışmayı
yorum veya disiplinle değil, **iki yönlü bir guard'la** ölçün
(`test-module-liste-esitligi.sh`: `A\B` ve `B\A` ayrı ayrı kırmızı).

**2. Bir jar ya module'de ya WAR'da — asla ikisinde.** Framework'ün kendi jar'ları (`zeus-*`)
bilinçli olarak module'e KONMAZ, WAR'da taşınır: böylece kod değişikliği module yenilemeyi
gerektirmez. Bu aynı zamanda "uygulama bu modülü pom'una yazmış mı" sorusunun güvenilir
sinyalidir.

**3. Paylaşımlı module bir BİRLEŞİMDİR — yan etkisini module'ü küçülterek çözmeyin.**
Module tüm uygulamaların bağımlılık birleşimi olduğu için, AI kullanmayan bir uygulama da
Spring AI'ın autoconfig'lerini classpath'inde görür ve Spring Boot onları yapılandırmaya
çalışır. Cazip çözüm olan "o yığını module'den çıkar" **paylaşımın kendisini çökertir**
(kullanan uygulama 15 jar'ı kendi WAR'ında taşımaya başlar). Doğru yer Spring seviyesidir:
yetenekler opt-in olur (`21-yetenek-opt-in.md`).

**4. Çalışma zamanı açık, build kapalı düşsün.** Framework'ün tanımadığı bir autoconfig
çalışma zamanında **veto edilmemelidir** — sınıflandırma eksiği çalışan bir uygulamayı
kırmamalı. Ama build o eksiği **kırmızıya** çevirmelidir. Bu asimetri bilinçlidir; iki yarısı
da gerekçesiyle yazılmalıdır, yoksa sonraki okuyan birini "sadeleştirip" atar.

### Acı tecrübe: ince WAR'da hangi Spring uzantısı çalışır

İnce WAR modelinde `spring-boot` jar'ı **paylaşımlı module'dedir** ve o classloader WAR'ın
`WEB-INF/lib`'indeki `META-INF/spring.factories` dosyalarını **göremez**.

| Uzantı | İnce WAR'da çalışır mı | Neden |
|---|---|---|
| `META-INF/spring/...AutoConfiguration.imports` | ✅ | Deployment classloader ile yüklenir |
| `AutoConfigurationImportFilter` (spring.factories) | ✅ | `AutoConfigurationImportSelector` onu deployment classloader'ıyla yükler |
| `EnvironmentPostProcessor` (spring.factories) | ❌ | Hiç keşfedilmez — **sessizce** |

Bu, birim testlerin **yakalayamadığı** bir sınıf hatadır: gömülü çalıştırmada (`spring-boot:run`)
sorunsuz çalışır, yalnız gerçek deploy'da ölü çıkar. Bu repoda iki kez ısırdı; ikisi de ancak
sunucuya deploy edildiğinde görüldü. `test-spring-factories-ince-war.sh` tekrarını engeller.
`zeus-logger`'ın `CorrelationLoggingEnvironmentPostProcessor`'ı bu yüzden ölüdür ve
`ZeusServletInitializer` ile telafi edilir — §2'deki 4. zorunluluğun sebebi budur.

---

## İlgili

- `17-module-yenileme-runbook.md` — module yenileme prosedürü ve tuzakları
- `19-war-paketleme-module-farkindaligi.md` — paketleme kuralı ve üretilen liste
- `21-yetenek-opt-in.md` — yetenek sözleşmesi
- `10-versiyonlu-slot-uretilen-descriptor.md` — slot'lar ve üretilen descriptor
- `14-uygulama-tipi-parentlar.md` — tip parent'ları
- `08-wildfly-module-dagitim.md` — module'ün içeriği neden o
