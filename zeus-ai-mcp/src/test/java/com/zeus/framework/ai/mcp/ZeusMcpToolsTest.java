package com.zeus.framework.ai.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Yayın sözleşmesinin doğrulamaları — hepsi BEAN KURULUŞ ANINDA, ilk isteği beklemeden.
 *
 * <p>Yanlış bir kayıt, ilk MCP çağrısında anlaşılmaz bir hata olarak değil, açılışta konuşan bir
 * hata olarak ortaya çıkmalı; yayın yüzeyi bu yüzden eager doğrulanır.
 */
class ZeusMcpToolsTest {

    static class AracsizNesne {
        public String birSeyYap() {
            return "x";
        }
    }

    /** {@link SahteAraclar} ile AYNI tool adını taşıyan ikinci nesne. */
    static class CakisanAraclar {
        @Tool(description = "Aynı adı taşıyan ikinci tool.")
        public java.util.List<String> listProducts() {
            return java.util.List.of();
        }
    }

    @Test
    void toolMetoduOlmayanNesneReddedilir() {
        // Spring AI'ın MethodToolCallbackProvider'ı IllegalArgumentException fırlatır ve mesajında
        // sınıf adını verir; biz onu sarmıyoruz — kendi mesajı zaten yönlendirici.
        assertThatThrownBy(() -> ZeusMcpTools.of(new AracsizNesne()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No @Tool annotated methods found")
                .hasMessageContaining("AracsizNesne");
    }

    @Test
    void ayniToolAdiIkiNesnedeReddedilir() {
        assertThatThrownBy(() -> ZeusMcpTools.of(new SahteAraclar(), new CakisanAraclar()))
                .hasMessageContaining("listProducts");
    }

    @Test
    void bosCagriReddedilirVeNoneOnerilir() {
        assertThatThrownBy(ZeusMcpTools::of)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("none()");
    }

    @Test
    void nullOgeReddedilir() {
        assertThatThrownBy(() -> ZeusMcpTools.of(new SahteAraclar(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ayniNesneIkiKezReddedilir() {
        SahteAraclar araclar = new SahteAraclar();
        assertThatThrownBy(() -> ZeusMcpTools.of(araclar, araclar))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("iki kez");
    }

    @Test
    void noneBosKayitUretir() {
        assertThatCode(() -> ZeusMcpTools.none()).doesNotThrowAnyException();
        assertThat(ZeusMcpTools.none().toolObjects()).isEmpty();
        assertThat(ZeusMcpTools.none().toolCallbacks()).isEmpty();
    }

    @Test
    void gecerliKayitIkiCallbackUretir() {
        ZeusMcpTools tools = ZeusMcpTools.of(new SahteAraclar());
        assertThat(tools.toolObjects()).hasSize(1);
        assertThat(tools.toolCallbacks())
                .extracting(cb -> cb.getToolDefinition().name())
                .containsExactlyInAnyOrder("listProducts", "findProductById");
    }
}
