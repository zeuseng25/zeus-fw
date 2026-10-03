package com.zeus.framework.ai.agent;

/**
 * Koşunun ölçümleri.
 *
 * <p>{@code stopReason} bilinçli olarak sonucun bir parçasıdır: "model bitirdi" ile "bütçe doldu"
 * aynı yanıt gövdesine karışmamalıdır. Kurumsal maliyet görünürlüğünün tek yolu budur.
 */
public record AgentRunStats(int steps, long tokens, long durationMs, StopReason stopReason) {
}
