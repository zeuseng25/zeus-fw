package com.zeus.framework.autoconfig;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ZeusCapabilities#HEPSI} içindeki ÖNEK SIRASININ kilidi.
 *
 * <p><b>Neden ayrı bir teste ihtiyaç var.</b> {@code sahipBul} bir önek listesinde
 * {@code findFirst()} ile gezer. {@code ai-mcp}'nin öneki ({@code org.springframework.ai.mcp.}),
 * {@code ai}'nin önekinin ({@code org.springframework.ai.}) ALT KÜMESİDİR. Satırlar ters sıraya
 * geçerse MCP autoconfig'lerini {@code ai} sahiplenir; {@code zeus.ai.mcp.enabled} hiçbir şey
 * yapmaz ve MCP ucu, AI yeteneğini açan HER uygulamada sessizce yayına girer — ayrı modül
 * kararının tamamı boşa çıkar.
 *
 * <p>Bu sessiz bozulmayı mevcut hiçbir guard yakalamaz: {@code test-autoconfig-sahipligi.sh}
 * "module'deki her autoconfig bir yeteneğe düşüyor mu?" sorusunu sorar, geniş önek de o soruya
 * "evet" dediği için ters sırada da YEŞİL kalır. Sıralamanın tek bekçisi bu testtir.
 */
class ZeusCapabilitiesSiralamaTest {

    @Test
    void mcpAutoconfigleriniAiMcpSahiplenir() {
        // Spring AI 2.0.1'de MCP sunucu autoconfig'lerinin tamamı bu paket altındadır
        // (jar'ların AutoConfiguration.imports dosyalarından doğrulandı, 2026-10-03).
        for (String sinif : new String[] {
                "org.springframework.ai.mcp.server.common.autoconfigure.McpServerAutoConfiguration",
                "org.springframework.ai.mcp.server.common.autoconfigure.ToolCallbackConverterAutoConfiguration",
                "org.springframework.ai.mcp.server.common.autoconfigure.McpServerJsonMapperAutoConfiguration",
                "org.springframework.ai.mcp.server.common.autoconfigure.annotations"
                        + ".McpServerAnnotationScannerAutoConfiguration",
                "org.springframework.ai.mcp.server.webmvc.autoconfigure"
                        + ".McpServerStreamableHttpWebMvcAutoConfiguration" }) {
            assertThat(ZeusCapabilities.sahipBul(sinif))
                    .withFailMessage("'%s' sınıfını 'ai-mcp' sahiplenmeli", sinif)
                    .isPresent()
                    .get()
                    .extracting(ZeusCapability::ad)
                    .isEqualTo("ai-mcp");
        }
    }

    @Test
    void mcpDisiSpringAiAutoconfigleriniAiSahiplenir() {
        for (String sinif : new String[] {
                "org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration",
                "org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration",
                "org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration" }) {
            assertThat(ZeusCapabilities.sahipBul(sinif))
                    .withFailMessage("'%s' sınıfını 'ai' sahiplenmeli", sinif)
                    .isPresent()
                    .get()
                    .extracting(ZeusCapability::ad)
                    .isEqualTo("ai");
        }
    }

    @Test
    void aiMcpSatiriAiSatirindanONCE_gelir() {
        // Yukarıdaki iki testin dayandığı yapısal koşul, doğrudan ve okunur biçimde.
        int aiMcp = indeks("ai-mcp");
        int ai = indeks("ai");
        assertThat(aiMcp)
                .withFailMessage("'ai-mcp' (%d) 'ai' (%d) satırından ÖNCE gelmeli — "
                        + "ZeusCapabilities.HEPSI sırası bozulmuş", aiMcp, ai)
                .isLessThan(ai);
    }

    private static int indeks(String ad) {
        for (int i = 0; i < ZeusCapabilities.HEPSI.size(); i++) {
            if (ZeusCapabilities.HEPSI.get(i).ad().equals(ad)) {
                return i;
            }
        }
        throw new AssertionError("Yetenek kaydı yok: " + ad);
    }
}
