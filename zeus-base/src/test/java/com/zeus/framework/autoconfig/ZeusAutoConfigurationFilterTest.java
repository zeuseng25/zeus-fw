package com.zeus.framework.autoconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
}
