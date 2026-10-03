package com.zeus.framework.ai.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class BudgetEligibilityCheckerTest {

    /** Araç çağrısı İÇEREN yanıt — döngünün devam etmek isteyeceği durum. */
    private static ChatResponse araçCagiranYanit(long promptTok, long completionTok) {
        AssistantMessage msg = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("id-1", "function", "ls", "{}")))
                .build();
        return new ChatResponse(List.of(new Generation(msg)),
                ChatResponseMetadata.builder()
                        .usage(new DefaultUsage((int) promptTok, (int) completionTok))
                        .build());
    }

    /** Araç çağrısı İÇERMEYEN yanıt — model işini bitirdi. */
    private static ChatResponse duzYanit() {
        return new ChatResponse(List.of(new Generation(new AssistantMessage("bitti"))));
    }

    @Test
    void aracCagrisiYoksaDonguDevamETMEZ() {
        var c = new BudgetEligibilityChecker(AgentBudget.defaults(), () -> 0L);
        assertThat(c.apply(duzYanit())).isFalse();
        assertThat(c.stopReason()).isEqualTo(StopReason.MODEL_FINISHED);
    }

    @Test
    void adimButcesiDolunca_durur() {
        var c = new BudgetEligibilityChecker(new AgentBudget(2, 1_000_000, Duration.ofHours(1)), () -> 0L);
        assertThat(c.apply(araçCagiranYanit(1, 1))).isTrue();   // 1. adım
        assertThat(c.apply(araçCagiranYanit(1, 1))).isTrue();   // 2. adım
        assertThat(c.apply(araçCagiranYanit(1, 1))).isFalse();  // bütçe doldu
        assertThat(c.stopReason()).isEqualTo(StopReason.STEP_BUDGET);
        assertThat(c.steps()).isEqualTo(2);
    }

    @Test
    void tokenButcesiDolunca_durur() {
        var c = new BudgetEligibilityChecker(new AgentBudget(100, 50, Duration.ofHours(1)), () -> 0L);
        assertThat(c.apply(araçCagiranYanit(20, 10))).isTrue();   // toplam 30
        assertThat(c.apply(araçCagiranYanit(20, 10))).isFalse();  // toplam 60 > 50
        assertThat(c.stopReason()).isEqualTo(StopReason.TOKEN_BUDGET);
        assertThat(c.tokens()).isEqualTo(60);
    }

    @Test
    void sureButcesiDolunca_durur() {
        AtomicLong saat = new AtomicLong(0);
        var c = new BudgetEligibilityChecker(
                new AgentBudget(100, 1_000_000, Duration.ofMillis(100)), saat::get);
        assertThat(c.apply(araçCagiranYanit(1, 1))).isTrue();
        saat.set(500);
        assertThat(c.apply(araçCagiranYanit(1, 1))).isFalse();
        assertThat(c.stopReason()).isEqualTo(StopReason.TIME_BUDGET);
    }

    @Test
    void varsayilanlarSikiDir() {
        AgentBudget b = AgentBudget.defaults();
        assertThat(b.maxSteps()).isEqualTo(15);
        assertThat(b.maxTokens()).isEqualTo(200_000);
        assertThat(b.maxDuration()).isEqualTo(Duration.ofMinutes(3));
    }
}
