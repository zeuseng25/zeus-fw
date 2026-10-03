package com.zeus.framework.ai.agent;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.model.tool.ToolExecutionEligibilityChecker;

import java.util.function.Supplier;

/**
 * Tool döngüsünün devam edip etmeyeceğine karar veren bütçe.
 *
 * <p>Spring AI'ın {@code ToolCallingAdvisor.Builder}'ında iterasyon sınırı yoktur; döngüye
 * müdahale edilebilen tek genişletme noktası budur. Bütçe dolduğunda döngü <b>temiz durur</b> —
 * istisna fırlatılmaz, çünkü o ana kadar üretilmiş rapor ve çalışma alanı korunmalıdır.
 *
 * <p><b>Koşu kapsamlıdır ve DURUM TUTAR</b> (adım/token sayacı). Her koşu için yeni bir örnek
 * kurulmalıdır; paylaşılan bir örnek koşular arasında sayaç sızdırır.
 */
public class BudgetEligibilityChecker implements ToolExecutionEligibilityChecker {

    private final AgentBudget budget;
    private final Supplier<Long> clockMillis;
    private final long startedAt;

    private int steps;
    private long promptTokens;
    private long completionTokens;
    private StopReason stopReason = StopReason.MODEL_FINISHED;

    public BudgetEligibilityChecker(AgentBudget budget, Supplier<Long> clockMillis) {
        this.budget = budget;
        this.clockMillis = clockMillis;
        this.startedAt = clockMillis.get();
    }

    @Override
    public Boolean apply(ChatResponse response) {
        Usage u = usage(response);
        if (u != null) {
            promptTokens += u.getPromptTokens();
            completionTokens += u.getCompletionTokens();
        }
        long tokens = promptTokens + completionTokens;

        // Model araç çağırmadıysa işi bitmiştir; bütçeye bakmaya gerek yok.
        if (!hasToolCalls(response)) {
            stopReason = StopReason.MODEL_FINISHED;
            return false;
        }
        if (steps >= budget.maxSteps()) {
            stopReason = StopReason.STEP_BUDGET;
            return false;
        }
        if (tokens > budget.maxTokens()) {
            stopReason = StopReason.TOKEN_BUDGET;
            return false;
        }
        if (clockMillis.get() - startedAt > budget.maxDuration().toMillis()) {
            stopReason = StopReason.TIME_BUDGET;
            return false;
        }
        steps++;
        return true;
    }

    public int steps() {
        return steps;
    }

    /** Toplam token (prompt + completion) — bütçe denetiminin karşılaştırdığı değer. */
    public long tokens() {
        return promptTokens + completionTokens;
    }

    public long promptTokens() {
        return promptTokens;
    }

    public long completionTokens() {
        return completionTokens;
    }

    public StopReason stopReason() {
        return stopReason;
    }

    private static Usage usage(ChatResponse response) {
        if (response == null || response.getMetadata() == null) {
            return null;
        }
        return response.getMetadata().getUsage();
    }

    private static boolean hasToolCalls(ChatResponse response) {
        if (response == null || response.getResults() == null || response.getResults().isEmpty()) {
            return false;
        }
        for (var generation : response.getResults()) {
            if (generation != null && generation.getOutput() instanceof AssistantMessage) {
                var msg = (AssistantMessage) generation.getOutput();
                if (msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }
}
