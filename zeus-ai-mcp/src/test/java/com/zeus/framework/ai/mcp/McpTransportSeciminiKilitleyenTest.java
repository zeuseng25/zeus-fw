package com.zeus.framework.ai.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerJsonMapperAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.ToolCallbackConverterAutoConfiguration;
import org.springframework.ai.mcp.server.webmvc.autoconfigure.McpServerSseWebMvcAutoConfiguration;
import org.springframework.ai.mcp.server.webmvc.autoconfigure.McpServerStreamableHttpWebMvcAutoConfiguration;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.web.servlet.function.RouterFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring AI'ın taşıma seçimi davranışını SABİTLER — ve bu davranış
 * {@code ZeusMcpAutoConfiguration}'daki protocol fail-fast kontrolünün gerekçesidir.
 *
 * <p><b>Ölçülen (2026-10-03, Spring AI 2.0.1):</b> {@code spring.ai.mcp.server.protocol}
 * yazılmazsa, iki webmvc autoconfig'i birlikte yüklü olsa bile ÇAKIŞMA OLMAZ; bunun yerine
 * <b>deprecated SSE taşıması</b> yayınlanır. Sebep: streamable autoconfig {@code STREAMABLE}'ı
 * açıkça ister ({@code matchIfMissing} yok), SSE ise {@code matchIfMissing=true} taşır.
 *
 * <p><b>Neden güvenlik meselesi:</b> iki taşımanın ucu ayrı property uzaylarından gelir —
 * SSE {@code spring.ai.mcp.server.sse-endpoint}, streamable
 * {@code spring.ai.mcp.server.streamable-http.mcp-endpoint}. {@link ZeusMcpTokenFilter} yolunu
 * streamable property'sinden türettiği için, SSE aktifken HİÇBİR ŞEYİ korumaz. Bu yüzden modülün
 * kuralı: yalnız {@code STREAMABLE} desteklenir, başka her değer açılışı reddeder.
 *
 * <p>Spring AI bir gün varsayılanı değiştirirse bu test kırmızıya döner ve fail-fast kontrolünün
 * gerekçesi yeniden değerlendirilir — testin asıl işi bu.
 */
class McpTransportSeciminiKilitleyenTest {

    private WebApplicationContextRunner runner(String... props) {
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        McpServerJsonMapperAutoConfiguration.class,
                        ToolCallbackConverterAutoConfiguration.class,
                        McpServerAutoConfiguration.class,
                        McpServerSseWebMvcAutoConfiguration.class,
                        McpServerStreamableHttpWebMvcAutoConfiguration.class))
                .withPropertyValues(props)
                .withBean("probe", ToolCallbackProvider.class,
                        () -> ToolCallbackProvider.from(ZeusMcpTools.of(new SahteAraclar()).toolCallbacks()));
    }

    @Test
    void protocolYazilmazsaSESSIZCE_SSE_yayinlanir() {
        runner().run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBeanNamesForType(RouterFunction.class))
                    .containsExactly("webMvcSseServerRouterFunction");
        });
    }

    @Test
    void streamableYazilincaStreamableYayinlanir() {
        runner("spring.ai.mcp.server.protocol=STREAMABLE").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBeanNamesForType(RouterFunction.class))
                    .containsExactly("webMvcStreamableServerRouterFunction");
        });
    }

    @Test
    void herDurumdaTamBirUcYayinlanir() {
        // Çakışma beklentisi (NoUniqueBeanDefinitionException) ÖLÇÜMLE ÇÜRÜTÜLDÜ: tehlike
        // "iki uç" değil, "yanlış ve korunmayan tek uç".
        for (String[] durum : new String[][] { {}, { "spring.ai.mcp.server.protocol=STREAMABLE" },
                { "spring.ai.mcp.server.protocol=SSE" } }) {
            runner(durum).run(ctx -> {
                assertThat(ctx).hasNotFailed();
                assertThat(ctx.getBeanNamesForType(RouterFunction.class)).hasSize(1);
            });
        }
    }
}
