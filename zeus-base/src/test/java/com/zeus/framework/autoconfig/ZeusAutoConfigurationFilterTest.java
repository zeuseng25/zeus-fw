package com.zeus.framework.autoconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

class ZeusAutoConfigurationFilterTest {

    private static final String AI    = "org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration";
    private static final String JDBC  = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration";
    private static final String MVC   = "org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration";
    private static final String DOC   = "org.springdoc.core.configuration.SpringDocConfiguration";
    private static final String SOAP  = "org.apache.cxf.spring.boot.autoconfigure.CxfAutoConfiguration";

    /** İşaretçi sınıf ADI = zeus-ai bağımlılığının WAR'da olmasının sinyali. */
    private static final String AI_ISARETCI = "com.zeus.framework.ai.ZeusAiAutoConfiguration";

    /**
     * Bu yardımcı, filtreyi HİÇBİR yetenek işaretçisinin çözülemediği bir classloader ile kurar
     * (aşağıdaki {@link #sayanYukleyici} — argümansız çağrıldığında hiçbir com.zeus.framework.*
     * sınıfını "var" saymaz). Çelişki denetimi bu yüzden HER ZAMAN koşar (artık beanClassLoader
     * null olsa bile koşar — bkz. {@link #beanClassLoaderYokkenKendiYukleyicisineDuserVeCalisir})
     * ama hiçbir işaretçi bulunamadığından sessiz kalır; bu, yalnız filtreleme davranışını (force
     * include, veto, hata mesajları) sınayan aşağıdaki testleri çelişki denetiminden yalıtır.
     */
    private boolean[] filtrele(MockEnvironment env, String... sinifar) {
        ZeusAutoConfigurationFilter f = new ZeusAutoConfigurationFilter();
        f.setEnvironment(env);
        f.setBeanClassLoader(sayanYukleyici(new AtomicInteger()));
        return f.match(sinifar, null);
    }

    /**
     * Yalnız adı verilen zeus işaretçilerini "WAR'da var" sayan, her sorguyu SAYAN yükleyici.
     * Spring gerçek koşuda filtreye bunun yerine WAR'ın deployment classloader'ını verir
     * (AutoConfigurationImportSelector.invokeAwareMethods → setBeanClassLoader).
     */
    private ClassLoader sayanYukleyici(AtomicInteger sayac, String... varOlanlar) {
        List<String> var = List.of(varOlanlar);
        return new ClassLoader(getClass().getClassLoader()) {
            @Override
            public Class<?> loadClass(String name) throws ClassNotFoundException {
                if (name.startsWith("com.zeus.framework.")) {
                    sayac.incrementAndGet();
                    if (!var.contains(name)) {
                        throw new ClassNotFoundException(name);
                    }
                }
                return super.loadClass(name);
            }
        };
    }

    private ZeusAutoConfigurationFilter filtreKur(MockEnvironment env, ClassLoader yukleyici) {
        ZeusAutoConfigurationFilter f = new ZeusAutoConfigurationFilter();
        f.setEnvironment(env);
        f.setBeanClassLoader(yukleyici);
        return f;
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

    @Test
    void forceIncludeBirdenFazlaSinifiVirgulleAyirir() {
        // force-include String[] olarak okunur; Spring'in dönüştürme servisi virgülle ayrılmış
        // değerleri böler ve trim eder — ama forceIncludeVetoyuEzer testi TEK sınıfla çağırdığı
        // için bu davranış kanıtsızdı. Burada iki yetenek (ai, database) birden zorla dahil
        // ediliyor, hiçbiri açık değil; ilişkisiz üçüncü bir yetenek (soap) hâlâ veto edilmeli.
        MockEnvironment env = new MockEnvironment()
                .withProperty("zeus.autoconfig.force-include", AI + "," + JDBC);
        boolean[] r = filtrele(env, AI, JDBC, SOAP);
        assertThat(r).containsExactly(true, true, false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TRUE", "1", "yes", "on"})
    void kabulEdilenBooleanBicimleriYeteneğiAcar(String deger) {
        MockEnvironment env = new MockEnvironment().withProperty("zeus.ai.enabled", deger);
        assertThat(filtrele(env, AI)).containsExactly(true);
    }

    @Test
    void bosPropertyDegeriVarsayilanaDuser() {
        // Boş değer dönüştürülmeye çalışılmaz, varsayılana (KAPALI) düşer.
        MockEnvironment env = new MockEnvironment().withProperty("zeus.ai.enabled", "");
        assertThat(filtrele(env, AI)).containsExactly(false);
    }

    @Test
    void gecersizYetenekDegeriAcikHataVerir() {
        // FAIL-OPEN burada geçerli DEĞİL: bu, uygulamanın kendi typo'lu yapılandırma hatası.
        // Sessizce false'a düşüp yeteneği kapatmak yerine, hatayı property adı ve verilen
        // değerle açıkça bildirmeli.
        MockEnvironment env = new MockEnvironment().withProperty("zeus.ai.enabled", "tru");
        assertThatThrownBy(() -> filtrele(env, AI))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("zeus.ai.enabled")
                .hasMessageContaining("tru");
    }

    @Test
    void gecersizFiltreKapatmaDegeriAcikHataVerir() {
        // Mekanizmanın kendi kaçış kapısı (zeus.autoconfig.filter.enabled) typo'landığında da
        // sessizce yutulmamalı — bu, güvenlik valfinin ta kendisi.
        MockEnvironment env = new MockEnvironment().withProperty("zeus.autoconfig.filter.enabled", "evet");
        assertThatThrownBy(() -> filtrele(env, AI))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("zeus.autoconfig.filter.enabled")
                .hasMessageContaining("evet");
    }

    // ───────── Çelişki denetimi ARTIK BU FİLTREDEN koşuyor (fix round 1) ─────────
    // Gerekçe: ilk evi olan EnvironmentPostProcessor ince WAR modelinde HİÇ çalışmıyordu;
    // bu filtre ise gerçek WildFly deploy'unda çalıştığı kanıtlanmış tek yol.

    @Test
    void celiskiFiltreninKendisindenFirlar() {
        // zeus-ai WAR'da (işaretçi çözülüyor) ama hiçbir bildirim yok → konuşan hata.
        AtomicInteger sayac = new AtomicInteger();
        ZeusAutoConfigurationFilter f =
                filtreKur(new MockEnvironment(), sayanYukleyici(sayac, AI_ISARETCI));

        assertThatThrownBy(() -> f.match(new String[] {MVC}, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Zeus yetenek bildirimi eksik")
                .hasMessageContaining("zeus.ai.enabled=true")
                .hasMessageContaining("pom.xml");
    }

    @Test
    void bilincliFalseFiltredenDeHataVermez() {
        // "Bağımlılığım var, yeteneği bilerek kapalı tutuyorum" MEŞRU cümledir (kusur 2).
        MockEnvironment env = new MockEnvironment().withProperty("zeus.ai.enabled", "false");
        ZeusAutoConfigurationFilter f =
                filtreKur(env, sayanYukleyici(new AtomicInteger(), AI_ISARETCI));

        assertThat(f.match(new String[] {AI, MVC}, null)).containsExactly(false, true);
    }

    @Test
    void celiskiDenetimiUygulamaBasinaBirKezKosar() {
        // Her filtrelenen sınıf için DEĞİL, her match() çağrısı için de DEĞİL: bir kez.
        AtomicInteger sayac = new AtomicInteger();
        MockEnvironment env = new MockEnvironment().withProperty("zeus.ai.enabled", "true");
        ZeusAutoConfigurationFilter f = filtreKur(env, sayanYukleyici(sayac, AI_ISARETCI));

        f.match(new String[] {AI, JDBC, MVC, DOC}, null);
        int ilkKosudakiSorgu = sayac.get();
        assertThat(ilkKosudakiSorgu).isEqualTo(ZeusCapabilities.HEPSI.size());  // sınıf başına değil

        f.match(new String[] {AI, JDBC, MVC, DOC}, null);
        assertThat(sayac.get()).isEqualTo(ilkKosudakiSorgu);                    // ikinci kez koşmadı
    }

    @Test
    void beanClassLoaderYokkenKendiYukleyicisineDuserVeCalisir() {
        // Fix round 2 (Task 6, Part B #1): beanClassLoader null ise ARTIK sessizce atlanmaz;
        // Boot'un kendi AutoConfigurationImportSelector#checkExcludedClasses'ıyla AYNI
        // fallback uygulanır: (beanClassLoader != null) ? beanClassLoader : getClass().getClassLoader().
        // (getConfigurationClassFilter() bu null kontrolünü yapmaz; deseni aynı sınıfın
        // checkExcludedClasses metodundan taklit ediyoruz.)
        // Bu sınıf (ZeusAutoConfigurationFilter) zeus-base jar'ının içinde yaşar ve zeus-base
        // her zaman WAR'ın WEB-INF/lib'indedir (zeus jar'ları com.zeus module'üne GİRMEZ) —
        // dolayısıyla kendi classloader'ı zaten doğru yükleyicidir.
        //
        // Kanıt setBeanClassLoader HİÇ çağrılmadan (beanClassLoader gerçekten null) elde edilir:
        // com.zeus.framework.ai.ZeusAiAutoConfiguration GERÇEKTEN bu test modülünün classpath'inde
        // (zeus-base/src/test/java/.../ai/ZeusAiAutoConfiguration.java — bkz. o dosyanın javadoc'u)
        // çözülebilir bir sınıf, yani fallback işaretçiyi BULUR ve zeus.ai.enabled hiç
        // bildirilmediği için çelişki denetimi FIRLAR — eski davranış (sessiz {false, true})
        // yerine artık konuşan bir hata verir.
        ZeusAutoConfigurationFilter f = new ZeusAutoConfigurationFilter();
        f.setEnvironment(new MockEnvironment());

        assertThatThrownBy(() -> f.match(new String[] {AI, MVC}, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Zeus yetenek bildirimi eksik")
                .hasMessageContaining("zeus.ai.enabled=true");
    }
}
