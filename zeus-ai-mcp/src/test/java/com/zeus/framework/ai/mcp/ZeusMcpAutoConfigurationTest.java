package com.zeus.framework.ai.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ZeusMcpAutoConfiguration}'ın yetenek opt-in'ine uyduğunun ve FAIL-CLOSED olduğunun kanıtı.
 *
 * <p>Simetri kilidi {@code ZeusSoapAutoConfigurationTest}'ten devralındı: framework'ün KENDİ hata
 * mesajı {@code zeus.<yetenek>.enabled=false} yazmayı önerdiği için, o cümle açılışı çökertmemeli.
 * Bu config'in {@code @ConditionalOnProperty} anahtarı, veto ettiği 3. parti yığınla aynı anahtar
 * olduğundan koşul sağlanır — test bunu kilitler.
 */
class ZeusMcpAutoConfigurationTest {

    private static final String[] GECERLI_TASIMA = {
            "spring.ai.mcp.server.protocol=STREAMABLE",
            "spring.ai.mcp.server.streamable-http.mcp-endpoint=/api/mcp"
    };

    private WebApplicationContextRunner web() {
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ZeusMcpAutoConfiguration.class));
    }

    @Test
    void bilincliFalseAcilisiCokertmez() {
        web().withPropertyValues("zeus.ai.mcp.enabled=false")
                .run(ctx -> assertThat(ctx)
                        .hasNotFailed()
                        .doesNotHaveBean(ZeusMcpAutoConfiguration.class)
                        .doesNotHaveBean(ZeusMcpTokenFilter.class)
                        .doesNotHaveBean(ToolCallbackProvider.class));
    }

    @Test
    void propertyHicYazilmamissaDaAcilisCokmez() {
        // Yetenekler OPT-IN'dir: bildirimi olmayan uygulama hiçbir MCP bean'i almadan ayakta kalır.
        web().run(ctx -> assertThat(ctx)
                .hasNotFailed()
                .doesNotHaveBean(ZeusMcpAutoConfiguration.class)
                .doesNotHaveBean(ZeusMcpTokenFilter.class));
    }

    @Test
    void tokenYoksaAcilisREDDEDILIR() {
        // FAIL-CLOSED kanıtı: yetenek açık ama sır yok → ağa açılmaz, açılış düşer.
        web().withPropertyValues("zeus.ai.mcp.enabled=true")
                .withPropertyValues(GECERLI_TASIMA)
                .run(ctx -> assertThat(ctx)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("zeus.ai.mcp.token"));
    }

    @Test
    void protocolYazilmamissaAcilisREDDEDILIR() {
        // Ölçülmüş tehlike: protocol yazılmazsa deprecated SSE yayınlanır ve token filtresi
        // onu korumaz (bkz. McpTransportSeciminiKilitleyenTest).
        web().withPropertyValues("zeus.ai.mcp.enabled=true", "zeus.ai.mcp.token=s3cret",
                        "spring.ai.mcp.server.streamable-http.mcp-endpoint=/api/mcp")
                .run(ctx -> assertThat(ctx)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("spring.ai.mcp.server.protocol"));
    }

    @Test
    void protocolSSEIseAcilisREDDEDILIR() {
        web().withPropertyValues("zeus.ai.mcp.enabled=true", "zeus.ai.mcp.token=s3cret",
                        "spring.ai.mcp.server.protocol=SSE",
                        "spring.ai.mcp.server.streamable-http.mcp-endpoint=/api/mcp")
                .run(ctx -> assertThat(ctx)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("STREAMABLE"));
    }

    @Test
    void ucYoluYazilmamissaAcilisREDDEDILIR() {
        web().withPropertyValues("zeus.ai.mcp.enabled=true", "zeus.ai.mcp.token=s3cret",
                        "spring.ai.mcp.server.protocol=STREAMABLE")
                .run(ctx -> assertThat(ctx)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("mcp-endpoint"));
    }

    @Test
    void kayitBeaniYoksaProviderOLUSMAZ() {
        // AÇIK YAYIN kanıtı: yetenek açık, taşıma ve sır tamam, ama ZeusMcpTools yok →
        // hiçbir tool yayınlanmaz. "Unutulan kayıt" sessiz bir yayına dönüşmez.
        web().withPropertyValues("zeus.ai.mcp.enabled=true", "zeus.ai.mcp.token=s3cret")
                .withPropertyValues(GECERLI_TASIMA)
                .run(ctx -> assertThat(ctx)
                        .hasNotFailed()
                        .hasSingleBean(ZeusMcpAutoConfiguration.class)
                        .hasSingleBean(ZeusMcpTokenFilter.class)
                        .doesNotHaveBean(ToolCallbackProvider.class));
    }

    @Test
    void kayitBeaniVarsaAraclarYayinlanir() {
        web().withPropertyValues("zeus.ai.mcp.enabled=true", "zeus.ai.mcp.token=s3cret")
                .withPropertyValues(GECERLI_TASIMA)
                .withBean(ZeusMcpTools.class, () -> ZeusMcpTools.of(new SahteAraclar()))
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed().hasSingleBean(ToolCallbackProvider.class);
                    // Yayınlanan her callback audit dekoratörüyle sarılmış olmalı.
                    assertThat(ctx.getBean(ToolCallbackProvider.class).getToolCallbacks())
                            .hasSize(2)
                            .allMatch(cb -> cb instanceof AuditingToolCallback);
                });
    }

    @Test
    void chatClientGerekmez_yalnizToolModuCalisir() {
        // zeus.ai.enabled=false + zeus.ai.mcp.enabled=true birinci sınıf moddur: uygulama kendi
        // LLM çağrısı yapmadan @Tool metotlarını dış ajanlara açar (ajan başka yerde, veri burada).
        // Bu yüzden bu config ChatClient/ZeusAiAssistant bean'i TALEP ETMEMELİ.
        web().withPropertyValues("zeus.ai.enabled=false",
                        "zeus.ai.mcp.enabled=true", "zeus.ai.mcp.token=s3cret")
                .withPropertyValues(GECERLI_TASIMA)
                .withBean(ZeusMcpTools.class, () -> ZeusMcpTools.of(new SahteAraclar()))
                .run(ctx -> assertThat(ctx)
                        .hasNotFailed()
                        .hasSingleBean(ToolCallbackProvider.class));
    }

    @Test
    void servletDisiOrtamdaYuklenmez() {
        // @ConditionalOnWebApplication(SERVLET): WebFlux ve servlet dışı ortamlar kapsam dışı.
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ZeusMcpAutoConfiguration.class))
                .withPropertyValues("zeus.ai.mcp.enabled=true", "zeus.ai.mcp.token=s3cret")
                .withPropertyValues(GECERLI_TASIMA)
                .run(ctx -> assertThat(ctx)
                        .hasNotFailed()
                        .doesNotHaveBean(ZeusMcpAutoConfiguration.class));
    }
}
