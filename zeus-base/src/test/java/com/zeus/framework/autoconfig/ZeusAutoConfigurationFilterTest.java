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
