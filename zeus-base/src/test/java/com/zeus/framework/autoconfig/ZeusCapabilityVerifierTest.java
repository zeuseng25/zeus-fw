package com.zeus.framework.autoconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void isaretciVarPropertyFalse_sessiz() {
        // BİLİNÇLİ KAPATMA çelişki DEĞİLDİR (fix round 1, kusur 2): "bağımlılık WAR'da var
        // ama bu yeteneği istemiyorum" demenin tek yolu budur. Önceki sürüm 'property != true'
        // baktığı için bu cümle kurulamıyordu; 'false' yazan uygulama da hata alıyordu.
        MockEnvironment env = new MockEnvironment().withProperty("zeus.ai.enabled", "false");
        assertThat(ZeusCapabilityVerifier.dogrula(
                env, yukleyici("com.zeus.framework.ai.ZeusAiAutoConfiguration"))).isEmpty();
    }

    @Test
    void isaretciVarBildirilmisAmaTypoluDeger_acikHata() {
        // Bildirim VAR ama değer typo'lu: yetenek autoconfig'leri aday listesine hiç girmese
        // bile sessiz kalınmaz — filtreyle aynı konuşan hata.
        MockEnvironment env = new MockEnvironment().withProperty("zeus.ai.enabled", "tru");
        assertThatThrownBy(() -> ZeusCapabilityVerifier.dogrula(
                env, yukleyici("com.zeus.framework.ai.ZeusAiAutoConfiguration")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("zeus.ai.enabled")
                .hasMessageContaining("tru");
    }

    @Test
    void denetleCelistiginde_acilisiDurduranHataAtar() {
        // denetle(), filtrenin çağırdığı giriş noktasıdır: liste boş değilse açılışı durdurur.
        assertThatThrownBy(() -> ZeusCapabilityVerifier.denetle(
                new MockEnvironment(), yukleyici("com.zeus.framework.ai.ZeusAiAutoConfiguration")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Zeus yetenek bildirimi eksik")
                .hasMessageContaining("zeus.ai.enabled=true")
                .hasMessageContaining("pom.xml");

        assertThatCode(() -> ZeusCapabilityVerifier.denetle(new MockEnvironment(), yukleyici()))
                .doesNotThrowAnyException();
    }

    @Test
    void filtreKapaliykenDenetimDeKapali() {
        // Kaçış kapısı tutarlı olmalı: mekanizma kapalıysa çelişki denetimi de susar.
        MockEnvironment env = new MockEnvironment().withProperty("zeus.autoconfig.filter.enabled", "false");
        assertThat(ZeusCapabilityVerifier.dogrula(
                env, yukleyici("com.zeus.framework.ai.ZeusAiAutoConfiguration"))).isEmpty();
    }
}
