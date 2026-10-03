package com.zeus.framework.ai.mcp;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerJsonMapperAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.ToolCallbackConverterAutoConfiguration;
import org.springframework.ai.mcp.server.webmvc.autoconfigure.McpServerStreamableHttpWebMvcAutoConfiguration;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Modülün TAŞIYICI VARSAYIMINI ölçer: mevcut {@code @Tool}/{@code @ToolParam} metotları, yeni bir
 * anotasyon olmadan MCP tool şemasına köprüleniyor mu?
 *
 * <p>Köprüyü Spring AI'ın {@code ToolCallbackConverterAutoConfiguration}'ı kurar; ondan beklenen
 * tek şey bir {@code ToolCallbackProvider} bean'i görünce {@code List<SyncToolSpecification>}
 * üretmesi. Bu doğru olduğu sürece zeus-ai-mcp şema üretmez.
 *
 * <p><b>Bu test KALICIDIR ve bir tripwire'dır.</b> Spring AI sürümü yükseltildiğinde köprünün
 * şekli değişirse burada kırmızıya döner — modülün R4 (spec/API churn) azaltımı budur.
 * İlk ölçüm: 2026-10-03, Spring AI 2.0.1 (bkz. .superpowers/sdd/.../task-0-report.md).
 */
class McpKopruOlcumuTest {

    private WebApplicationContextRunner runner() {
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        McpServerJsonMapperAutoConfiguration.class,
                        ToolCallbackConverterAutoConfiguration.class,
                        McpServerAutoConfiguration.class,
                        McpServerStreamableHttpWebMvcAutoConfiguration.class))
                .withPropertyValues("spring.ai.mcp.server.protocol=STREAMABLE");
    }

    @Test
    void zeusMcpToolsIleKaydedilenAraclarMcpSemasinaDonusur() {
        runner()
                .withBean("probe", ToolCallbackProvider.class,
                        () -> ToolCallbackProvider.from(ZeusMcpTools.of(new SahteAraclar()).toolCallbacks()))
                .run(ctx -> {
                    // 1) çıplak List<ToolCallback> parametresi, sıfır ToolCallback bean'i varken
                    //    context'i düşürmüyor
                    assertThat(ctx).hasNotFailed();

                    // 2) spec sayısı @Tool metot sayısı kadar
                    List<McpServerFeatures.SyncToolSpecification> specs = syncTools(ctx.getBean("syncTools"));
                    assertThat(specs).hasSize(2);

                    // 3) tool adları metot adları
                    assertThat(specs.stream().map(s -> s.tool().name()).toList())
                            .containsExactlyInAnyOrder("listProducts", "findProductById");

                    // 4) @ToolParam açıklaması input şemasına geçiyor — "açıklama bir yorum değil,
                    //    ARAYÜZ SÖZLEŞMESİDİR": model tool'u bu metinle seçer.
                    String sema = specs.stream()
                            .filter(s -> s.tool().name().equals("findProductById"))
                            .findFirst().orElseThrow()
                            .tool().inputSchema().toString();
                    assertThat(sema).contains("Ürünün sayısal id'si");

                    // 5) sunucu kuruldu
                    assertThat(ctx).hasSingleBean(McpSyncServer.class);
                });
    }

    @Test
    void providerYoksaSunucuSifirToolIleAcilir() {
        // "Yetenek açık, kayıt bean'i yok" durumunun güvenli ve gözlenebilir olduğunun kanıtı.
        runner().run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(ToolCallbackProvider.class);
        });
    }

    @SuppressWarnings("unchecked")
    private static List<McpServerFeatures.SyncToolSpecification> syncTools(Object bean) {
        return (List<McpServerFeatures.SyncToolSpecification>) bean;
    }
}
