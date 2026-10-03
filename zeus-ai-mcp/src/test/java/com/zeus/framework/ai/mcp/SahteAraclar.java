package com.zeus.framework.ai.mcp;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;

/**
 * Test fixture'ı — kanıt uygulamasındaki {@code ProductAiTools}'un ŞEKLİNİ birebir taklit eder
 * (biri parametresiz liste dönen, biri {@code @ToolParam} açıklamalı parametre alan iki metot).
 *
 * <p>Şekil kasıtlı: testlerin kanıtladığı şey "bir anotasyon çalışıyor" değil, "uygulamanın
 * GERÇEKTE yazdığı biçim MCP'ye köprüleniyor".
 */
class SahteAraclar {

    @Tool(description = "Katalogdaki tüm ürünleri listeler.")
    public List<String> listProducts() {
        return List.of("a", "b");
    }

    @Tool(description = "Verilen id'ye sahip ürünü döner.")
    public String findProductById(@ToolParam(description = "Ürünün sayısal id'si") Long id) {
        return "ürün-" + id;
    }
}
