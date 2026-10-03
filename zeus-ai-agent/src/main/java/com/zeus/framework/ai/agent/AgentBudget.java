package com.zeus.framework.ai.agent;

import java.time.Duration;

/**
 * Bir ajan koşusunun sınırları.
 *
 * <p><b>Neden zorunlu:</b> Spring AI'ın tool döngüsünde iterasyon sınırı YOKTUR; bir hata döngüsü
 * fatura patlatabilir. Varsayılanlar bilinçli olarak SIKIDIR — uzun koşular için çağıran bunları
 * açıkça yükseltmelidir.
 */
public record AgentBudget(int maxSteps, long maxTokens, Duration maxDuration) {

    public AgentBudget {
        if (maxSteps < 1) {
            throw new IllegalArgumentException("maxSteps en az 1 olmalı: " + maxSteps);
        }
        if (maxTokens < 1) {
            throw new IllegalArgumentException("maxTokens en az 1 olmalı: " + maxTokens);
        }
        if (maxDuration == null || maxDuration.isNegative() || maxDuration.isZero()) {
            throw new IllegalArgumentException("maxDuration pozitif olmalı: " + maxDuration);
        }
    }

    /** 15 adım · 200.000 token · 3 dakika. */
    public static AgentBudget defaults() {
        return new AgentBudget(15, 200_000L, Duration.ofMinutes(3));
    }
}
