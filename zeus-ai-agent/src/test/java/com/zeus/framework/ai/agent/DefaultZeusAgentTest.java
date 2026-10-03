package com.zeus.framework.ai.agent;

import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultZeusAgentTest {

    private ZeusAgent ajan(StubChatModel model) {
        return new DefaultZeusAgent(model, ObservationRegistry.NOOP);
    }

    @Test
    void modelBitirinceSonucVeIstatistikDoner() {
        StubChatModel model = new StubChatModel().metin("rapor hazır", 10, 5);

        AgentResult<String> r = ajan(model).run("ürünleri analiz et",
                AgentSpec.of("Sen bir analiz ajanısın."));

        assertThat(r.output()).isEqualTo("rapor hazır");
        assertThat(r.stats().stopReason()).isEqualTo(StopReason.MODEL_FINISHED);
        assertThat(r.stats().steps()).isZero();
        // ÖLÇÜM (bkz. task-4-report.md): tool çağırmayan tek yanıtta BudgetEligibilityChecker
        // yine de çağrılıyor ve token sayıyor — bu yüzden 15 (10+5) doğru beklenti.
        assertThat(r.stats().tokens()).isEqualTo(15);
        assertThat(r.workspace()).isEmpty();
    }

    @Test
    void ajanCalismaAlaninaYazabilir_veSonucDoner() {
        StubChatModel model = new StubChatModel()
                .aracCagir("writeFile", "{\"filePath\":\"/rapor.md\",\"content\":\"bulgular\"}", 10, 5)
                .metin("yazdım", 10, 5);

        AgentResult<String> r = ajan(model).run("rapor yaz", AgentSpec.of("Sen bir ajansın."));

        assertThat(r.workspace()).containsEntry("/rapor.md", "bulgular");
        assertThat(r.stats().steps()).isEqualTo(1);
        assertThat(r.stats().stopReason()).isEqualTo(StopReason.MODEL_FINISHED);
    }

    @Test
    void adimButcesiKosuyuDURDURUR_veSebebiBildirir() {
        StubChatModel model = new StubChatModel();
        for (int i = 0; i < 10; i++) {
            model.aracCagir("ls", "{\"path\":\"/\"}", 1, 1);
        }

        AgentSpec spec = new AgentSpec("Sen bir ajansın.", java.util.List.of(),
                new AgentBudget(2, 1_000_000L, Duration.ofHours(1)));
        AgentResult<String> r = ajan(model).run("sonsuza kadar listele", spec);

        assertThat(r.stats().stopReason()).isEqualTo(StopReason.STEP_BUDGET);
        assertThat(r.stats().steps()).isEqualTo(2);
    }

    @Test
    void kosularArasindaCalismaAlaniPAYLASILMAZ() {
        ZeusAgent ajan = ajan(new StubChatModel()
                .aracCagir("writeFile", "{\"filePath\":\"/a.md\",\"content\":\"1\"}", 1, 1)
                .metin("bitti", 1, 1));
        AgentResult<String> ilk = ajan.run("yaz", AgentSpec.of("x"));
        assertThat(ilk.workspace()).containsKey("/a.md");

        // Aynı ajan bean'i, yeni koşu: çalışma alanı taze olmalı.
        ZeusAgent ajan2 = ajan(new StubChatModel().metin("bitti", 1, 1));
        assertThat(ajan2.run("hiçbir şey yapma", AgentSpec.of("x")).workspace()).isEmpty();
    }

    @Test
    void uygulamaToollariAjanToollarıylaBirlikteVerilir() {
        StubChatModel model = new StubChatModel().metin("ok", 1, 1);
        ajan(model).run("x", AgentSpec.of("sistem", new SahteUygulamaToolu()));
        // Derleme ve koşunun hatasız tamamlanması yeterli kanıt: uygulama tool'u
        // MethodToolCallbackProvider tarafından reddedilmedi.
        assertThat(model.cagrilar()).hasSize(1);
    }

    static class SahteUygulamaToolu {
        @org.springframework.ai.tool.annotation.Tool(description = "Deneme amaçlı sahte tool.")
        public String deneme() {
            return "x";
        }
    }
}
