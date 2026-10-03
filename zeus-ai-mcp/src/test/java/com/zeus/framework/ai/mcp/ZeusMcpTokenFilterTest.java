package com.zeus.framework.ai.mcp;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Token filtresinin sözleşmesi: doğru sır geçer, yanlış ve eksik sır AYNI yanıtı alır,
 * uygulamanın kendi uçlarına dokunulmaz.
 */
class ZeusMcpTokenFilterTest {

    private static final String UC = "/api/mcp";
    private static final String TOKEN = "d0gru-sir";

    private ZeusMcpTokenFilter filtre() {
        ZeusMcpProperties p = new ZeusMcpProperties();
        p.setToken(TOKEN);
        return new ZeusMcpTokenFilter(UC, p);
    }

    private MockHttpServletRequest istek(String yol, String token) {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", yol);
        r.setServletPath(yol);
        if (token != null) {
            r.addHeader("X-Zeus-Mcp-Token", token);
        }
        return r;
    }

    @Test
    void dogruTokenZinciriGecer() throws Exception {
        MockFilterChain zincir = new MockFilterChain();
        MockHttpServletResponse yanit = new MockHttpServletResponse();

        filtre().doFilter(istek(UC, TOKEN), yanit, zincir);

        assertThat(yanit.getStatus()).isEqualTo(200);
        assertThat(zincir.getRequest()).isNotNull();
    }

    @Test
    void yanlisVeEksikTokenBIREBIR_AYNI_yaniti_alir() throws Exception {
        // ORACLE YOK: istemci "header'ı mı unuttum, değeri mi yanlış" sorusunu yanıttan
        // ayırt edemez. Ayrım yalnızca logda.
        MockHttpServletResponse eksik = new MockHttpServletResponse();
        filtre().doFilter(istek(UC, null), eksik, new MockFilterChain());

        MockHttpServletResponse yanlis = new MockHttpServletResponse();
        filtre().doFilter(istek(UC, "yanlis-sir"), yanlis, new MockFilterChain());

        assertThat(eksik.getStatus()).isEqualTo(401);
        assertThat(yanlis.getStatus()).isEqualTo(401);
        // Charset de yanıta giriyor ("application/problem+json;charset=UTF-8") — Türkçe gövde
        // için istenen davranış bu; iddia medya tipini kontrol eder, charset'i sabitlemez.
        assertThat(eksik.getContentType()).startsWith("application/problem+json");
        assertThat(yanlis.getContentType()).isEqualTo(eksik.getContentType());
        assertThat(yanlis.getContentAsString()).isEqualTo(eksik.getContentAsString());
    }

    @Test
    void reddedilenYanitTokenSizdirmaz() throws Exception {
        MockHttpServletResponse yanit = new MockHttpServletResponse();
        filtre().doFilter(istek(UC, "yanlis-sir"), yanit, new MockFilterChain());

        assertThat(yanit.getContentAsString())
                .doesNotContain(TOKEN)
                .doesNotContain("yanlis-sir")
                .contains("MCP erişimi reddedildi");
    }

    @Test
    void bosTokenHeaderiDaReddedilir() throws Exception {
        MockHttpServletResponse yanit = new MockHttpServletResponse();
        filtre().doFilter(istek(UC, "   "), yanit, new MockFilterChain());
        assertThat(yanit.getStatus()).isEqualTo(401);
    }

    @Test
    void uygulamaninKendiUclarinaDOKUNULMAZ() throws Exception {
        // Filtre yalnız MCP ucunu korur; /api/products tokensız da geçmeli.
        MockFilterChain zincir = new MockFilterChain();
        MockHttpServletResponse yanit = new MockHttpServletResponse();

        filtre().doFilter(istek("/api/products", null), yanit, zincir);

        assertThat(yanit.getStatus()).isEqualTo(200);
        assertThat(zincir.getRequest()).isNotNull();
    }

    @Test
    void servletPathBosKalirsaRequestUriKullanilir() throws Exception {
        // Bazı konteyner/forward senaryolarında servletPath boş gelir; yol çözümü düşmemeli.
        MockHttpServletRequest r = new MockHttpServletRequest("POST", UC);
        r.setServletPath("");
        r.setRequestURI(UC);
        MockHttpServletResponse yanit = new MockHttpServletResponse();
        FilterChain zincir = new MockFilterChain();

        filtre().doFilter(r, yanit, zincir);

        assertThat(yanit.getStatus()).isEqualTo(401);
    }
}
