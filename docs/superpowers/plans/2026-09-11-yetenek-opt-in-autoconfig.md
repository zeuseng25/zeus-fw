# Yetenek opt-in'i — Uygulama Planı

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Steps use `- [ ]` checkboxes.

**Goal:** Paylaşımlı `com.zeus` module'ündeki 3. parti autoconfig'ler yalnız o yeteneği açıkça isteyen uygulamalarda çalışsın.

**Architecture:** `zeus-base`'e bir `AutoConfigurationImportFilter` (Spring'in resmî kancası) + yetenek kaydı + "bağımlılık var ama property yok" çelişkisini açılışta yakalayan bir `EnvironmentPostProcessor`. Çalışma zamanı fail-open, build fail-closed (yeni guard).

**Tech Stack:** Java 25, Spring Boot 4.0.7, JUnit 5 + AssertJ (`spring-boot-starter-test`, zeus-parent'tan), bash guard script'leri.

**Spec:** `docs/superpowers/specs/2026-09-11-yetenek-opt-in-autoconfig-design.md`

## Global Constraints

- Her `mvn` öncesi: `export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home`
- Kod/yorum/doküman **Türkçe**; `.md` yalnız `zeus-fw`'ye.
- **`zeus-base`'in yetenek modüllerine derleme zamanı bağımlılığı YOKTUR.** İşaretçi sınıflar ve autoconfig adları **dize** olarak tutulur, `Class.forName` ile çözülür. Bu kural kırılırsa `zeus-base` her şeyi çeker ve paylaşımlı module fikri çöker.
- **Çalışma zamanı fail-open:** filtre tanımadığı sınıfı veto ETMEZ. **Build fail-closed:** sınıflandırılmamış autoconfig guard'ı kırar.
- Test script'leri `set -uo pipefail` (`-e` YOK) — hataları toplayıp sonunda raporlarlar. `set -euo pipefail` kullanan beş script'in ERR trap'i korunur.
- Guard'lar `mvn`/`unzip` başarısızlığında ve boş çıktıda **hard failure** verir; sessiz yeşil yasak.
- Varsayılan KAPALI; `springdoc` yetenek DEĞİL, her uygulamada açık kalır.
- Sunucuya yazan script'ler (`install-zeus-module.sh`) yalnız Task 5'te ve yalnız açıkça söylendiğinde çalıştırılır.

---

### Task 1: SOAP istemcisi varsayımını ölç (spec Risk 4)

Spec bu ölçüm yapılmadan yetenek kaydının kesinleşmiş sayılmayacağını söylüyor. `zeus-sms` CXF'i **istemci** olarak kullanır ve `zeus-soap`'a bağlı değildir; `zeus.soap.enabled` CXF autoconfig'lerini veto ettiğinde SMS istemcisi hâlâ çalışmalı.

**Files:**
- Test: `zeus-sms/src/test/java/com/zeus/framework/sms/CxfAutoConfigYokkenIstemciTest.java`

**Interfaces:**
- Produces: bu görevin ÇIKTISI bir karardır — `soap` yeteneğinin autoconfig önek listesi Task 2'de bu sonuca göre yazılır.

- [ ] **Step 1: Testi yaz** — CXF autoconfig'leri hiç yokken `JaxWsProxyFactoryBean`'in çalıştığını kanıtla.

```java
package com.zeus.framework.sms;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.cxf.jaxws.JaxWsProxyFactoryBean;
import org.junit.jupiter.api.Test;

/**
 * Spec Risk 4'ün ölçümü: zeus.soap.enabled yazılmamış bir uygulamada CXF autoconfig'leri
 * veto edilir. SMS İSTEMCİSİ o durumda da çalışmalı — çünkü zeus-sms, zeus-soap'a bağlı
 * değildir ve proxy'yi kendisi kurar. Bu test Spring context'i HİÇ kurmaz; tam olarak
 * "autoconfig yok" durumunu temsil eder.
 */
class CxfAutoConfigYokkenIstemciTest {

    @Test
    void springYokkenDeProxyKurulur() {
        JaxWsProxyFactoryBean f = new JaxWsProxyFactoryBean();
        f.setServiceClass(SmsService.class);
        f.setAddress("http://localhost:1/sms");

        SmsService proxy = (SmsService) f.create();

        // Proxy kuruldu: CXF varsayılan Bus'ı kendi oluşturdu, Spring'e ihtiyaç duymadı.
        assertThat(proxy).isNotNull();
        assertThat(org.apache.cxf.frontend.ClientProxy.getClient(proxy)).isNotNull();
    }
}
```

- [ ] **Step 2: Çalıştır**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home && mvn -q -pl zeus-sms test -Dtest=CxfAutoConfigYokkenIstemciTest`
Beklenen: PASS.

- [ ] **Step 3: Sonucu raporla ve kararı yaz.**
  - **PASS ise:** `soap` yeteneği spec'teki gibi kalır (`org.apache.cxf.spring.boot.autoconfigure.` öneki veto edilir). Raporuna "Risk 4 ölçüldü: istemci CXF autoconfig'i olmadan çalışıyor" yaz.
  - **FAIL ise:** DURMA, raporla — kontrolör karar verecek. Muhtemel çözüm: `org.apache.cxf.spring.boot.autoconfigure.CxfAutoConfiguration`'ı "her zaman serbest" listesine alıp yalnız `...jaxws.CxfJaxwsAutoConfiguration` ve `...openapi.`/`...micrometer.` sınıflarını veto etmek (sunucu tarafı = endpoint yayınlama).

- [ ] **Step 4: Commit**

```bash
git add zeus-sms/src/test/java/com/zeus/framework/sms/CxfAutoConfigYokkenIstemciTest.java
git commit -m "Risk 4 ölçümü: SMS istemcisi CXF autoconfig'i olmadan çalışıyor mu"
```

---

### Task 2: Yetenek kaydı + filtre

**Files:**
- Create: `zeus-base/src/main/java/com/zeus/framework/autoconfig/ZeusCapability.java`
- Create: `zeus-base/src/main/java/com/zeus/framework/autoconfig/ZeusCapabilities.java`
- Create: `zeus-base/src/main/java/com/zeus/framework/autoconfig/ZeusAutoConfigurationFilter.java`
- Create: `zeus-base/src/main/resources/META-INF/spring.factories`
- Test: `zeus-base/src/test/java/com/zeus/framework/autoconfig/ZeusAutoConfigurationFilterTest.java`

**Interfaces:**
- Consumes: Task 1'in kararı (soap önek listesi).
- Produces:
  - `ZeusCapability` — `record ZeusCapability(String ad, String property, String isaretciSinif, List<String> autoconfigOnekleri)`
  - `ZeusCapabilities.HEPSI` — `List<ZeusCapability>`
  - `ZeusCapabilities.sahipBul(String autoconfigSinifAdi)` → `Optional<ZeusCapability>`
  - `ZeusCapabilities.HER_ZAMAN_SERBEST` — `List<String>` (önekler)

- [ ] **Step 1: Başarısız testi yaz**

```java
package com.zeus.framework.autoconfig;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ZeusAutoConfigurationFilterTest {

    private static final String AI    = "org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration";
    private static final String JDBC  = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration";
    private static final String MVC   = "org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration";
    private static final String DOC   = "org.springdoc.core.configuration.SpringDocConfiguration";

    private boolean[] filtrele(MockEnvironment env, String... sinifar) {
        ZeusAutoConfigurationFilter f = new ZeusAutoConfigurationFilter();
        f.setEnvironment(env);
        return f.match(sinifar, null);
    }

    @Test
    void yetenekKapaliykenSiniflariVetoEdilir() {
        // Hiçbir property yok: varsayılan KAPALI.
        boolean[] r = filtrele(new MockEnvironment(), AI, JDBC);
        assertThat(r).containsExactly(false, false);
    }

    @Test
    void yetenekAcikkenGecer() {
        MockEnvironment env = new MockEnvironment().withProperty("zeus.ai.enabled", "true");
        boolean[] r = filtrele(env, AI, JDBC);
        assertThat(r).containsExactly(true, false);  // ai açık, database değil
    }

    @Test
    void sahipsizSinifHerZamanGecer() {
        // Çekirdek web yığını ve springdoc yeteneğe ait DEĞİL — hiçbir koşulda veto edilmez.
        boolean[] r = filtrele(new MockEnvironment(), MVC, DOC);
        assertThat(r).containsExactly(true, true);
    }

    @Test
    void forceIncludeVetoyuEzer() {
        MockEnvironment env = new MockEnvironment().withProperty("zeus.autoconfig.force-include", AI);
        assertThat(filtrele(env, AI)).containsExactly(true);
    }

    @Test
    void filtreKapatilabilir() {
        MockEnvironment env = new MockEnvironment().withProperty("zeus.autoconfig.filter.enabled", "false");
        assertThat(filtrele(env, AI, JDBC)).containsExactly(true, true);
    }

    @Test
    void tanimadigiSinifiVetoEtmez() {
        // FAIL-OPEN: framework'ün eksik sınıflandırması bir uygulamayı kırmamalı.
        assertThat(filtrele(new MockEnvironment(), "com.baska.FirmaAutoConfiguration")).containsExactly(true);
    }
}
```

- [ ] **Step 2: Çalıştır, KIRMIZI olduğunu gör**

Run: `mvn -q -pl zeus-base test -Dtest=ZeusAutoConfigurationFilterTest`
Beklenen: derleme hatası — `ZeusAutoConfigurationFilter` yok.

- [ ] **Step 3: `ZeusCapability` kaydını yaz**

```java
package com.zeus.framework.autoconfig;

import java.util.List;

/**
 * Bir zeus yeteneğinin sözleşmesi.
 *
 * @param ad                kısa ad (log/hata mesajlarında görünür), ör. "ai"
 * @param property          uygulamanın yazacağı anahtar, ör. "zeus.ai.enabled"
 * @param isaretciSinif     yetenek modülünün kendi *AutoConfiguration sınıfının TAM ADI.
 *                          DİZE olarak tutulur: zeus-base bu modüllere derleme zamanında
 *                          bağlanmaz (bağlansa her şeyi çeker, paylaşımlı module çöker).
 * @param autoconfigOnekleri bu yeteneğin SAHİPLENDİĞİ 3. parti autoconfig paket önekleri
 */
public record ZeusCapability(String ad, String property, String isaretciSinif,
                             List<String> autoconfigOnekleri) {

    /** Verilen autoconfig sınıfı bu yeteneğe mi ait? */
    public boolean sahiplenir(String autoconfigSinifAdi) {
        return autoconfigOnekleri.stream().anyMatch(autoconfigSinifAdi::startsWith);
    }
}
```

- [ ] **Step 4: `ZeusCapabilities` kaydını yaz**

```java
package com.zeus.framework.autoconfig;

import java.util.List;
import java.util.Optional;

/**
 * Yetenek kaydı — paylaşımlı com.zeus module'ündeki 3. parti autoconfig'lerin sahiplik tablosu.
 *
 * NEDEN VAR: com.zeus tüm uygulamaların bağımlılık BİRLEŞİMİDİR. AI geliştiren uygulama da
 * geliştirmeyen de aynı module'ü paylaşır; module küçültülmez (bu, paylaşımın kendisini
 * çökertirdi). Bu yüzden daraltma Spring seviyesinde yapılır: bir yeteneğin autoconfig'leri
 * yalnız uygulama o yeteneği AÇIKÇA istediğinde çalışır.
 *
 * BURAYA YENİ YETENEK EKLERKEN: scripts/test-autoconfig-sahipligi.sh, module'deki her
 * autoconfig sınıfının ya bir yeteneğe ya da HER_ZAMAN_SERBEST'e düştüğünü denetler.
 * Sınıflandırılmamış bir autoconfig build'i KIRAR — sessizce her uygulamada çalışmaya başlamaz.
 */
public final class ZeusCapabilities {

    private ZeusCapabilities() {
    }

    public static final List<ZeusCapability> HEPSI = List.of(
            new ZeusCapability("ai", "zeus.ai.enabled",
                    "com.zeus.framework.ai.ZeusAiAutoConfiguration",
                    List.of("org.springframework.ai.")),

            new ZeusCapability("database", "zeus.database.enabled",
                    "com.zeus.framework.database.ZeusDatabaseAutoConfiguration",
                    List.of("org.springframework.boot.jdbc.autoconfigure.",
                            "org.springframework.boot.hibernate.autoconfigure.",
                            "org.springframework.boot.data.jpa.autoconfigure.",
                            "org.springframework.boot.persistence.autoconfigure.")),

            new ZeusCapability("soap", "zeus.soap.enabled",
                    "com.zeus.framework.soap.ZeusSoapAutoConfiguration",
                    List.of("org.apache.cxf.spring.boot.autoconfigure.")));

    /**
     * Hiçbir yeteneğe ait olmayan, HER uygulamada çalışması gereken yığın.
     * springdoc bilinçli olarak buradadır: Swagger her uygulamada varsayılan açıktır (karar 5).
     */
    public static final List<String> HER_ZAMAN_SERBEST = List.of(
            "org.springframework.boot.autoconfigure.",
            "org.springframework.boot.webmvc.autoconfigure.",
            "org.springframework.boot.servlet.autoconfigure.",
            "org.springframework.boot.jackson.autoconfigure.",
            "org.springframework.boot.validation.autoconfigure.",
            "org.springframework.boot.http.",
            "org.springframework.boot.restclient.autoconfigure.",
            "org.springframework.boot.webclient.autoconfigure.",
            "org.springframework.boot.reactor.autoconfigure.",
            "org.springframework.boot.transaction.autoconfigure.",
            "org.springframework.boot.transaction.jta.autoconfigure.",
            "org.springframework.boot.data.autoconfigure.",
            "org.springdoc.");

    public static Optional<ZeusCapability> sahipBul(String autoconfigSinifAdi) {
        return HEPSI.stream().filter(y -> y.sahiplenir(autoconfigSinifAdi)).findFirst();
    }
}
```

- [ ] **Step 5: Filtreyi yaz**

```java
package com.zeus.framework.autoconfig;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.AutoConfigurationImportFilter;
import org.springframework.boot.autoconfigure.AutoConfigurationMetadata;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;

/**
 * Yeteneğe ait 3. parti autoconfig'leri, uygulama o yeteneği açıkça istemedikçe eler.
 *
 * Spring'in RESMÎ uzantı noktasıdır: Boot'un kendi OnClassCondition / OnBeanCondition /
 * OnWebApplicationCondition sınıfları da aynı kancayla (META-INF/spring.factories) kayıtlıdır.
 * Context kurulmadan ÖNCE çalışır; maliyeti ad karşılaştırmasıdır.
 *
 * FAIL-OPEN: tanımadığı sınıfı veto ETMEZ. Framework'ün eksik bir sınıflandırması üretimi
 * düşürmemeli — sınıflandırma eksiğini build zamanında guard yakalar.
 */
public class ZeusAutoConfigurationFilter implements AutoConfigurationImportFilter, EnvironmentAware {

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public boolean[] match(String[] autoConfigurationClasses, AutoConfigurationMetadata metadata) {
        boolean[] sonuc = new boolean[autoConfigurationClasses.length];

        // Kaçış kapısı 1: mekanizmayı tamamen kapat (bu değişiklik öncesi davranış).
        if (!environment.getProperty("zeus.autoconfig.filter.enabled", Boolean.class, true)) {
            Arrays.fill(sonuc, true);
            return sonuc;
        }

        // Kaçış kapısı 2: adı verilen autoconfig'ler veto edilmez (yanlış sınıflandırma kurtarması).
        List<String> zorlaDahil = List.of(
                environment.getProperty("zeus.autoconfig.force-include", String[].class, new String[0]));

        for (int i = 0; i < autoConfigurationClasses.length; i++) {
            String sinif = autoConfigurationClasses[i];
            // Spring bu diziye null koyabilir (zaten elenmiş adaylar) — dokunma.
            if (sinif == null) {
                sonuc[i] = true;
                continue;
            }
            if (zorlaDahil.contains(sinif)) {
                sonuc[i] = true;
                continue;
            }
            Optional<ZeusCapability> sahip = ZeusCapabilities.sahipBul(sinif);
            sonuc[i] = sahip.isEmpty()
                    || environment.getProperty(sahip.get().property(), Boolean.class, false);
        }
        return sonuc;
    }
}
```

- [ ] **Step 6: `spring.factories`'e kaydet**

`zeus-base/src/main/resources/META-INF/spring.factories`:

```properties
# Yetenek opt-in filtresi. Spring Boot'un kendi koşul sınıfları da bu anahtarla kayıtlıdır
# (bkz. spring-boot-autoconfigure!/META-INF/spring.factories).
org.springframework.boot.autoconfigure.AutoConfigurationImportFilter=\
com.zeus.framework.autoconfig.ZeusAutoConfigurationFilter
```

- [ ] **Step 7: Testleri çalıştır, YEŞİL olduğunu gör**

Run: `mvn -q -pl zeus-base test -Dtest=ZeusAutoConfigurationFilterTest`
Beklenen: 6 test PASS.

- [ ] **Step 8: Commit**

```bash
git add zeus-base/src/main/java/com/zeus/framework/autoconfig/ zeus-base/src/main/resources/META-INF/spring.factories zeus-base/src/test/java/com/zeus/framework/autoconfig/
git commit -m "Yetenek kaydı ve opt-in autoconfig filtresi"
```

---

### Task 3: Çelişki denetimi (konuşan hata)

**Files:**
- Create: `zeus-base/src/main/java/com/zeus/framework/autoconfig/ZeusCapabilityVerifier.java`
- Modify: `zeus-base/src/main/resources/META-INF/spring.factories`
- Test: `zeus-base/src/test/java/com/zeus/framework/autoconfig/ZeusCapabilityVerifierTest.java`

**Interfaces:**
- Consumes: `ZeusCapabilities.HEPSI`, `ZeusCapability.isaretciSinif()`, `ZeusCapability.property()`
- Produces: `ZeusCapabilityVerifier.dogrula(Environment, ClassLoader)` → `List<String>` (hata mesajları; boşsa sorun yok). `postProcessEnvironment` bu listeyi kullanır ve boş değilse `IllegalStateException` atar.

- [ ] **Step 1: Başarısız testi yaz**

```java
package com.zeus.framework.autoconfig;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ZeusCapabilityVerifierTest {

    /** Yalnız adı verilen sınıfları "classpath'te var" sayan sahte yükleyici. */
    private ClassLoader yukleyici(String... varOlanlar) {
        List<String> var = List.of(varOlanlar);
        return new ClassLoader(getClass().getClassLoader()) {
            @Override
            public Class<?> loadClass(String name) throws ClassNotFoundException {
                if (name.startsWith("com.zeus.framework.") && !var.contains(name)) {
                    throw new ClassNotFoundException(name);
                }
                return super.loadClass(name);
            }
        };
    }

    @Test
    void isaretciYokPropertyYok_sessiz() {
        // REST-only uygulama: hiçbir yetenek bildirmez. Bu MEŞRU bir durumdur, hata değil.
        assertThat(ZeusCapabilityVerifier.dogrula(new MockEnvironment(), yukleyici())).isEmpty();
    }

    @Test
    void isaretciVarPropertyYok_hata() {
        // ÇELİŞKİ: uygulama zeus-ai'ı pom'una yazmış ama açmamış.
        List<String> hatalar = ZeusCapabilityVerifier.dogrula(
                new MockEnvironment(), yukleyici("com.zeus.framework.ai.ZeusAiAutoConfiguration"));

        assertThat(hatalar).hasSize(1);
        assertThat(hatalar.get(0))
                .contains("zeus-ai")
                .contains("zeus.ai.enabled")
                .contains("pom.xml");   // düzeltmenin İKİ yolunu da söylemeli
    }

    @Test
    void isaretciVarPropertyVar_sessiz() {
        MockEnvironment env = new MockEnvironment().withProperty("zeus.ai.enabled", "true");
        assertThat(ZeusCapabilityVerifier.dogrula(
                env, yukleyici("com.zeus.framework.ai.ZeusAiAutoConfiguration"))).isEmpty();
    }

    @Test
    void filtreKapaliykenDenetimDeKapali() {
        // Kaçış kapısı tutarlı olmalı: mekanizma kapalıysa çelişki denetimi de susar.
        MockEnvironment env = new MockEnvironment().withProperty("zeus.autoconfig.filter.enabled", "false");
        assertThat(ZeusCapabilityVerifier.dogrula(
                env, yukleyici("com.zeus.framework.ai.ZeusAiAutoConfiguration"))).isEmpty();
    }
}
```

- [ ] **Step 2: Çalıştır, KIRMIZI olduğunu gör**

Run: `mvn -q -pl zeus-base test -Dtest=ZeusCapabilityVerifierTest`
Beklenen: derleme hatası — `ZeusCapabilityVerifier` yok.

- [ ] **Step 3: Verifier'ı yaz**

```java
package com.zeus.framework.autoconfig;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;

/**
 * "Bağımlılık var ama property yok" ÇELİŞKİSİNİ açılışta yakalar.
 *
 * Bu, opt-in'den opt-out'a dönüş DEĞİLDİR: bildirilmemiş bir yetenek meşrudur (REST-only
 * uygulama hiçbir şey yazmaz ve hata almaz). Hata yalnız uygulama yetenek modülünü pom'una
 * yazmış AMA açmamışsa çıkar — yani niyet ile yapılandırma çeliştiğinde.
 *
 * İşaretçi sınıfın sinyal olmasının sebebi: install-zeus-module.sh'ın EXCLUDE_REGEX'i
 * 'zeus-[a-z0-9-]+' içerir, yani zeus jar'ları com.zeus module'üne GİRMEZ, WAR'da taşınır.
 * Dolayısıyla sınıfın classpath'te olması = uygulamanın o bağımlılığı kendi pom'una yazması.
 */
public class ZeusCapabilityVerifier implements EnvironmentPostProcessor {

    static List<String> dogrula(Environment environment, ClassLoader classLoader) {
        List<String> hatalar = new ArrayList<>();
        if (!environment.getProperty("zeus.autoconfig.filter.enabled", Boolean.class, true)) {
            return hatalar;   // mekanizma kapalıysa denetim de susar
        }
        for (ZeusCapability y : ZeusCapabilities.HEPSI) {
            boolean isaretciVar;
            try {
                Class.forName(y.isaretciSinif(), false, classLoader);
                isaretciVar = true;
            } catch (ClassNotFoundException | LinkageError e) {
                isaretciVar = false;
            }
            if (isaretciVar && !environment.getProperty(y.property(), Boolean.class, false)) {
                hatalar.add(("'zeus-%s' bağımlılığı bu uygulamanın WAR'ında var ama '%s' yazılmamış. "
                        + "'%s' yeteneği KAPALI kalır ve bean'leri kurulmaz.%n"
                        + "      Kullanacaksanız application.properties'e ekleyin:  %s=true%n"
                        + "      Kullanmayacaksanız pom.xml'den zeus-%s bağımlılığını kaldırın.")
                        .formatted(y.ad(), y.property(), y.ad(), y.property(), y.ad()));
            }
        }
        return hatalar;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        List<String> hatalar = dogrula(environment, application.getClassLoader());
        if (!hatalar.isEmpty()) {
            throw new IllegalStateException("Zeus yetenek bildirimi eksik:%n      %s"
                    .formatted(String.join(System.lineSeparator() + "      ", hatalar)));
        }
    }
}
```

- [ ] **Step 4: `spring.factories`'e ekle**

Dosya şu hâle gelir:

```properties
# Yetenek opt-in filtresi. Spring Boot'un kendi koşul sınıfları da bu anahtarla kayıtlıdır
# (bkz. spring-boot-autoconfigure!/META-INF/spring.factories).
org.springframework.boot.autoconfigure.AutoConfigurationImportFilter=\
com.zeus.framework.autoconfig.ZeusAutoConfigurationFilter

# "Bağımlılık var ama property yok" çelişkisini açılışta yakalar.
org.springframework.boot.env.EnvironmentPostProcessor=\
com.zeus.framework.autoconfig.ZeusCapabilityVerifier
```

- [ ] **Step 5: Testleri çalıştır, YEŞİL olduğunu gör**

Run: `mvn -q -pl zeus-base test`
Beklenen: Task 2 ve Task 3 testlerinin hepsi PASS.

- [ ] **Step 6: Commit**

```bash
git add zeus-base/src/main/java/com/zeus/framework/autoconfig/ZeusCapabilityVerifier.java zeus-base/src/main/resources/META-INF/spring.factories zeus-base/src/test/java/com/zeus/framework/autoconfig/ZeusCapabilityVerifierTest.java
git commit -m "Yetenek çelişkisini açılışta yakalayan verifier"
```

---

### Task 4: `zeus.ai.enabled`'ı opt-in'e çevir + sahiplik guard'ı

**Files:**
- Modify: `zeus-ai/src/main/java/com/zeus/framework/ai/ZeusAiAutoConfiguration.java:31`
- Create: `scripts/test-autoconfig-sahipligi.sh`
- Modify: `scripts/run-guards.sh`

**Interfaces:**
- Consumes: `ZeusCapabilities.HEPSI`, `ZeusCapabilities.HER_ZAMAN_SERBEST` (guard bunları JAVA KAYNAĞINDAN okur, kopyalamaz)

- [ ] **Step 1: `matchIfMissing`'i çevir**

`ZeusAiAutoConfiguration.java:31`, şu satır:

```java
@ConditionalOnProperty(prefix = "zeus.ai", name = "enabled", havingValue = "true", matchIfMissing = true)
```

şununla değişir:

```java
// matchIfMissing=false: yetenekler OPT-IN'dir (bkz. com.zeus.framework.autoconfig.ZeusCapabilities).
// Aynı anahtar hem bu autoconfig'i hem Spring AI'ın 3. parti autoconfig'lerini yönetir —
// uygulamanın öğrenmesi gereken tek kavram olsun diye.
@ConditionalOnProperty(prefix = "zeus.ai", name = "enabled", havingValue = "true")
```

- [ ] **Step 2: Guard'ı yaz** — `scripts/test-autoconfig-sahipligi.sh`

Amaç: kurulu module'deki HER autoconfig sınıfı ya bir yeteneğe ya da `HER_ZAMAN_SERBEST`'e düşmeli. Düşmeyen varsa KIRMIZI — yeni bir yetenek module'e girdiğinde sessizce her uygulamada çalışmaya başlamasın.

```bash
#!/usr/bin/env bash
# Kurulu com.zeus module'ündeki her 3. parti autoconfig sınıfı SINIFLANDIRILMIŞ mı?
#
# NEDEN: com.zeus tüm uygulamaların birleşimidir. Sınıflandırılmamış bir autoconfig,
# onu istemeyen HER uygulamada çalışır (bugünkü spring-ai ağrısının kaynağı). Çalışma
# zamanı fail-open olduğu için (filtre tanımadığını veto etmez) yakalama yeri BURASIDIR.
#
# Sınıflandırma listesi ZeusCapabilities.java'dan OKUNUR, buraya kopyalanmaz —
# test-com-zeus-jakarta-api-kapsama.sh ile aynı "türet, sabitleme" disiplini.
set -uo pipefail

FW_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WILDFLY_HOME="${WILDFLY_HOME:-/Users/omer/workspaces/intellij/wildfly-41/wildfly-41.0.0.Final}"
MODULE_DIR="${WILDFLY_HOME}/modules/com/zeus/main"
KAYIT="${FW_ROOT}/zeus-base/src/main/java/com/zeus/framework/autoconfig/ZeusCapabilities.java"
fail=0

echo "── test-autoconfig-sahipligi.sh"

[[ -f "${KAYIT}" ]] || { echo "  ❌ yetenek kaydı bulunamadı: ${KAYIT}"; exit 1; }
[[ -d "${MODULE_DIR}" ]] || { echo "  ❌ module kurulu değil: ${MODULE_DIR}"; echo "     Önce: ./scripts/install-zeus-module.sh"; exit 1; }

# 1) Sınıflandırma önekleri: kaynak dosyadaki "..." dizelerinden, paket adı görünenler.
ONEKLER="$(grep -oE '"[a-z][a-zA-Z0-9_.]*\.(autoconfigure\.|)"' "${KAYIT}" | tr -d '"' | sort -u)"
if [[ -z "${ONEKLER}" ]]; then
    echo "  ❌ ZeusCapabilities.java'dan hiç önek çıkarılamadı — ÖLÇÜM HATASI (sessiz yeşil yasak)."
    exit 1
fi

# 2) Module'deki tüm autoconfig sınıfları.
SINIFLAR="$(for j in "${MODULE_DIR}"/*.jar; do
    unzip -p "${j}" "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports" 2>/dev/null
done | grep -vE '^\s*(#|$)' | sort -u)"
if [[ -z "${SINIFLAR}" ]]; then
    echo "  ❌ module'de hiç autoconfig sınıfı bulunamadı — ÖLÇÜM HATASI."
    exit 1
fi
echo "  >> ${MODULE_DIR##*/modules/}: $(wc -l <<< "${SINIFLAR}" | tr -d ' ') autoconfig sınıfı, $(wc -l <<< "${ONEKLER}" | tr -d ' ') önek"

# 3) Her sınıf bir öneke düşmeli.
SAHIPSIZ=""
while IFS= read -r sinif; do
    [[ -z "${sinif}" ]] && continue
    bulundu=0
    while IFS= read -r onek; do
        [[ "${sinif}" == "${onek}"* ]] && { bulundu=1; break; }
    done <<< "${ONEKLER}"
    (( bulundu )) || SAHIPSIZ+="${sinif}"$'\n'
done <<< "${SINIFLAR}"

if [[ -n "${SAHIPSIZ//[$'\n' ]/}" ]]; then
    echo "  ❌ SINIFLANDIRILMAMIŞ autoconfig (bu sınıflar HER uygulamada çalışır):"
    sed '/^$/d' <<< "${SAHIPSIZ}" | head -20 | sed 's/^/       /'
    adet=$(sed '/^$/d' <<< "${SAHIPSIZ}" | wc -l | tr -d ' ')
    (( adet > 20 )) && echo "       … ve $(( adet - 20 )) tane daha"
    echo "     Çözüm: ${KAYIT##*/} içinde ya bir yeteneğe ya da HER_ZAMAN_SERBEST'e ekleyin."
    fail=1
else
    echo "  ✅ module'deki her autoconfig sınıfı sınıflandırılmış"
fi

exit "${fail}"
```

- [ ] **Step 3: Guard'ı `run-guards.sh`'a kaydet.** Mevcut `SUITE` tablosundaki satırların biçimini birebir taklit et; etiket `wf` (kurulu module gerektirir).

- [ ] **Step 4: Guard'ın KIRILABİLDİĞİNİ kanıtla.** `ZeusCapabilities.java`'daki `"org.springdoc."` önekini geçici olarak sil → guard KIRMIZI olmalı (16 springdoc sınıfı sahipsiz kalır). Geri al → YEŞİL. Her iki çıktıyı da rapora koy.

- [ ] **Step 5: Tüm süiti çalıştır**

Run: `./scripts/run-guards.sh`
Beklenen: yeni guard dahil hepsi yeşil.

- [ ] **Step 6: Commit**

```bash
git add zeus-ai/src/main/java/com/zeus/framework/ai/ZeusAiAutoConfiguration.java scripts/test-autoconfig-sahipligi.sh scripts/run-guards.sh
git commit -m "zeus.ai.enabled opt-in oldu + autoconfig sahiplik guard'ı"
```

---

### Task 5: Gerçek deploy — problemin ta kendisi olan senaryo

Bu görev **sunucuya yazar**: `install-zeus-module.sh` çalıştırılır ve WildFly başlatılır. Sunucu şu an KAPALI ve tüm WAR'lar `.undeployed`; **bitince aynı hâlde bırakılmalı**.

**Files:**
- Modify: `../zeus-sample-soap/src/main/resources/application.properties` (ayrı repo, ayrı commit, `.md` yazma)
- Modify: `../spring-wildfly-arch/src/main/resources/application.properties` (ayrı repo, ayrı commit)

- [ ] **Step 1: Framework'ü kur ve module'ü yenile**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
mvn -q clean install
./scripts/install-zeus-module.sh
```

`main` slot güncellendiği için WildFly yüklüyse RESTART şart — sunucu zaten kapalı olduğu için bu koşuda ek maliyet yok.

- [ ] **Step 2: `zeus-sample-soap`'ın workaround'unu SİL.** Bu, işin bittiğinin kanıtıdır. Şu blok tamamen kaldırılır:

```properties
spring.autoconfigure.exclude=\
  org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,\
  org.springframework.boot.jdbc.autoconfigure.DataSourceInitializationAutoConfiguration,\
  org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration,\
  org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration

spring.ai.openai.api-key=kullanilmiyor
```

Yerine yeteneğin kendisi yazılır (bu uygulama SOAP endpoint yayınlar):

```properties
# Yetenek opt-in'i (bkz. zeus-fw/gelistirmeler/21-yetenek-opt-in.md): bu uygulama SOAP
# endpoint yayınlar, veritabanı ve AI kullanmaz. Kullanmadıklarını YAZMASI GEREKMEZ.
zeus.soap.enabled=true
```

- [ ] **Step 3: `spring-wildfly-arch`'a `zeus.database.enabled=true` ekle** (JPA/DataSource kullanıyor).

- [ ] **Step 4: İkisini de build et ve deploy et.** WildFly'ı başlat, iki WAR'ı da `standalone/deployments`'a koy.

- [ ] **Step 5: Kanıtı topla — gerçek çıktılarla, özet değil:**
  - `server.log`'da `ERROR` / `LinkageError` / `ClassCastException` / `NoClassDefFoundError` / `OutOfMemory` **yok**
  - `spring-wildfly-arch`: `GET /api/products` → **200**
  - `zeus-sample-soap`: `GET .../urunSorgu?wsdl` → **200**, SOAP POST → **200**
  - `server.log`'da `OpenAiChatAutoConfiguration` **hiç yapılandırılmamış** — `spring.ai.openai.api-key` hiçbir yerde yok ve uygulama yine açılıyor. **Bu satır bu planın varlık sebebidir.**
  - `/swagger-ui` hâlâ çalışıyor (karar 5: springdoc yetenek değil)

- [ ] **Step 6: Çelişki hatasını GERÇEKTE gör.** `spring-wildfly-arch`'tan `zeus.database.enabled` satırını geçici olarak sil, yeniden deploy et → açılışta Task 3'ün konuşan hatası çıkmalı. Çıktıyı rapora koy, sonra satırı geri al ve yeniden deploy et.

- [ ] **Step 7: Sunucuyu bulduğun gibi bırak** — WAR'ları kaldır, WildFly'ı durdur.

- [ ] **Step 8: Commit** (framework tarafında değişiklik yok; iki uygulama reposunda AYRI commit'ler).

---

### Task 6: Dokümanlar

**Files:**
- Create: `gelistirmeler/21-yetenek-opt-in.md`
- Modify: `gelistirmeler/08-wildfly-module-dagitim.md` — "Module geniştir, uygulama dardır" bölümü
- Modify: `CLAUDE.md`

- [ ] **Step 1: `gelistirmeler/21-yetenek-opt-in.md`'i yaz.** İçermesi gerekenler: problem (module birleşimdir, AI'lı ve AI'sız uygulama aynı module'ü paylaşır), karar (opt-in), API (`zeus.<yetenek>.enabled`), üç parça (kayıt/filtre/verifier), kaçış kapıları, fail-open ↔ fail-closed asimetrisi ve **gerekçesi**, yetenek tablosu, "yeni yetenek eklerken ne yapılır" adımları.

- [ ] **Step 2: Doküman 08'in "Module geniştir, uygulama dardır — daraltma uygulamanın işidir" bölümünü güncelle.** O bölüm bugün çözüm olarak elle `spring.autoconfigure.exclude` yazmayı söylüyor; artık **çözüm değil**. Başlığı da düzelt: daraltma artık uygulamanın değil framework'ün işi. Eski metni tarihsel not olarak bırak (bu repodaki yerleşik desen).

- [ ] **Step 3: `CLAUDE.md`'ye yetenek opt-in'ini ekle** — "Auto-Configuration Deseni" bölümünün yanına, yeni modül eklerken yetenek kaydına da eklenmesi gerektiği kuralıyla.

- [ ] **Step 4:** Ölçümler Task 5'in gerçek çıktılarından; doğrulanmayana ✅ yazma.

- [ ] **Step 5: Commit**

---

## Self-review notları (plan yazarı)

- **Spec kapsamı:** Risk 4 → Task 1; kayıt+filtre → Task 2; verifier → Task 3; `matchIfMissing` + guard → Task 4; entegrasyon testleri → Task 5; dokümanlar → Task 6. Kaçış kapıları Task 2'de test ediliyor. Fail-open Task 2 Step 1'in son testi, fail-closed Task 4 Step 4.
- **`zeus-redis`/`zeus-batch`** bilinçli olarak kayıtta yok (spec "kapsam dışı"): iskelet hâldeler ve module'de değiller.
- **Bilinen kırılganlık:** Task 4'ün guard'ı önekleri Java kaynağından `grep` ile çıkarır. Kaynak biçimi değişirse (ör. önekler bir sabit listeden gelmeye başlarsa) `grep` boş döner — bu yüzden boş çıktı **hard failure**'dır, sessizce yeşil geçmez.
