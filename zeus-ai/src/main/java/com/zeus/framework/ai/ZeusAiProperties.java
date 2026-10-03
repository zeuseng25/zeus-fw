package com.zeus.framework.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Zeus AI modülü ayarları ({@code zeus.ai.*}).
 *
 * <p>Model sağlayıcısının kendi ayarları (endpoint, anahtar, model adı) burada DEĞİL,
 * Spring AI'ın kendi property'lerindedir ({@code spring.ai.openai.*}) — framework onları
 * kopyalamaz, yalnızca üzerine kurumsal davranış ekler.
 */
@ConfigurationProperties(prefix = "zeus.ai")
public class ZeusAiProperties {

    /** Modül tümüyle devre dışı bırakılabilir (ör. AI'sız ortamlar, testler). */
    private boolean enabled = true;

    /** Tüm isteklere uygulanan ortak sistem promptu (kurumsal ton/kısıtlar). */
    private String systemPrompt = """
            Sen kurumsal bir Java uygulamasının yardımcı asistanısın.
            Yanıtların Türkçe, kısa ve olgusal olsun; emin olmadığın bilgiyi uydurma.
            Sana araç (tool) verildiyse veriyi tahmin etmek yerine aracı çağırarak öğren.
            """;

    /** İstek/yanıt gövdesini DEBUG seviyesinde loglar (SimpleLoggerAdvisor). Üretimde kapalı tutun. */
    private boolean logConversation = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public boolean isLogConversation() {
        return logConversation;
    }

    public void setLogConversation(boolean logConversation) {
        this.logConversation = logConversation;
    }
}
