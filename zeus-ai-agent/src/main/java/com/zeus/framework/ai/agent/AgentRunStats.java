package com.zeus.framework.ai.agent;

/**
 * Koşunun ölçümleri.
 *
 * <p>{@code stopReason} bilinçli olarak sonucun bir parçasıdır: "model bitirdi" ile "bütçe doldu"
 * aynı yanıt gövdesine karışmamalıdır. Kurumsal maliyet görünürlüğünün tek yolu budur.
 *
 * <p>{@code promptTokens}/{@code completionTokens} ayrı tutulur ({@code Usage.getPromptTokens()}
 * / {@code getCompletionTokens()}) — kurumsal maliyet raporlaması ikisini farklı fiyatlandırır.
 * {@link #tokens()} ikisinin toplamıdır; bütçe denetimi (bkz. {@code BudgetEligibilityChecker})
 * zaten bu toplama karşı çalışır, bu yüzden kısa yol olarak burada da tutulur.
 *
 * <p>{@code toolCalls} (A2'nin tool-call-kaydı dekoratörüyle birlikte gelecek) BİLİNÇLİ olarak
 * henüz YOK — bkz. tasarım dokümanı, "stats sözleşmesi" notu.
 */
public record AgentRunStats(int steps, long promptTokens, long completionTokens, long durationMs,
                             StopReason stopReason) {

    /** Toplam token (prompt + completion) — bütçe denetiminin karşılaştırdığı değer. */
    public long tokens() {
        return promptTokens + completionTokens;
    }
}
