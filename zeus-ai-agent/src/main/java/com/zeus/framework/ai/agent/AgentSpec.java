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
 * @param budget       adım/token/süre sınırları
 */
public record AgentSpec(String systemPrompt, List<Object> tools, AgentBudget budget) {

    public AgentSpec {
        tools = tools == null ? List.of() : List.copyOf(tools);
        budget = budget == null ? AgentBudget.defaults() : budget;
    }

    /** Varsayılan bütçeyle kısa yol. */
    public static AgentSpec of(String systemPrompt, Object... tools) {
        return new AgentSpec(systemPrompt, Arrays.asList(tools), AgentBudget.defaults());
    }
}
