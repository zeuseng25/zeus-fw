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
