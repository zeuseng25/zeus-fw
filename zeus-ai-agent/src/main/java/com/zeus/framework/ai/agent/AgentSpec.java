package com.zeus.framework.ai.agent;

import java.util.Arrays;
import java.util.List;

/**
 * Bir ajan koşusunun tanımı.
 *
 * @param systemPrompt ajanın rolü ve kısıtları
 * @param tools        {@code @Tool} anotasyonlu uygulama nesneleri — ZeusAiAssistant ile AYNI
 *                     üslup; uygulama yeni bir anotasyon öğrenmez. Çalışma alanı tool'ları
 *                     framework tarafından ayrıca eklenir.
 * @param budget       adım/token/süre sınırları. {@code null} ise ajanın KENDİ YAPILANDIRILMIŞ
 *                     varsayılan bütçesi kullanılır (bkz. {@code ZeusAgentProperties.toBudget()});
 *                     burada sabit bir varsayılana DÜŞÜLMEZ — aksi hâlde
 *                     {@code zeus.ai.agent.max-steps} gibi property'ler etkisiz kalırdı.
 */
public record AgentSpec(String systemPrompt, List<Object> tools, AgentBudget budget) {

    public AgentSpec {
        tools = tools == null ? List.of() : List.copyOf(tools);
        // DİKKAT: null budget BİLİNÇLİ olarak burada doldurulmaz. Dolduruluyormuş gibi
        // davranmak (AgentBudget.defaults()), ajanın yapılandırılmış varsayılanını
        // (zeus.ai.agent.*) sessizce ETKİSİZ kılardı. null, "ajanın varsayılanını kullan"
        // anlamına gelir; çözüm DefaultZeusAgent'ta yapılır.
    }

    /** Ajanın yapılandırılmış varsayılan bütçesiyle kısa yol (budget = null). */
    public static AgentSpec of(String systemPrompt, Object... tools) {
        return new AgentSpec(systemPrompt, Arrays.asList(tools), null);
    }
}
