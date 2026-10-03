package com.zeus.framework.ai.agent;

import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultZeusAgentTest {

    private ZeusAgent ajan(StubChatModel model) {
        // AgentBudget.defaults() AÇIKÇA verilir — DefaultZeusAgent artık bütçeyi parametre
        // olarak zorunlu alıyor (C1); burada "varsayılan" üretimin kendisi test EDİLMİYOR,
        // o yapiladirilanVarsayilanButceGercektenUygulanir testinde ayrıca sınanıyor.
        return new DefaultZeusAgent(model, ObservationRegistry.NOOP, AgentBudget.defaults());
    }

    record Bulgu(String ozet) {
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
        assertThat(r.stats().promptTokens()).isEqualTo(10);
        assertThat(r.stats().completionTokens()).isEqualTo(5);
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

    /**
     * C1'in ASIL kanıtı: {@code AgentSpec.of(...)} bütçeyi {@code null} bırakır (artık
     * {@code AgentBudget.defaults()}'a düşmez); ajanın KURULUŞTA verilen yapılandırılmış
     * varsayılanı gerçekten uygulanmalı. Bütçe dolu olmasa 15 adımda değil burada verilen
     * küçük sınırda durmalı — C1 ÖNCESİ bu test 15'te dururdu (ölçüm: task raporunda).
     */
    @Test
    void yapilandirilmisVarsayilanButceGercektenUygulanir() {
        StubChatModel model = new StubChatModel();
        for (int i = 0; i < 15; i++) {
            model.aracCagir("ls", "{\"path\":\"/\"}", 1, 1);
        }
        AgentBudget kucukYapilandirilmisVarsayilan = new AgentBudget(3, 1_000_000L, Duration.ofHours(1));
        ZeusAgent ajan = new DefaultZeusAgent(model, ObservationRegistry.NOOP, kucukYapilandirilmisVarsayilan);

        // AgentSpec.of(...) budget=null döner — ajanın kendi varsayılanı devreye girmeli.
        AgentResult<String> r = ajan.run("sonsuza kadar listele", AgentSpec.of("Sen bir ajansın."));

        assertThat(r.stats().stopReason()).isEqualTo(StopReason.STEP_BUDGET);
        assertThat(r.stats().steps()).isEqualTo(3);
    }

    @Test
    void specTeAcikcaVerilenButceAjaninVarsayilaniniEZER() {
        StubChatModel model = new StubChatModel();
        for (int i = 0; i < 15; i++) {
            model.aracCagir("ls", "{\"path\":\"/\"}", 1, 1);
        }
        AgentBudget genisYapilandirilmisVarsayilan = new AgentBudget(100, 1_000_000L, Duration.ofHours(1));
        ZeusAgent ajan = new DefaultZeusAgent(model, ObservationRegistry.NOOP, genisYapilandirilmisVarsayilan);

        AgentSpec spec = new AgentSpec("Sen bir ajansın.", List.of(),
                new AgentBudget(4, 1_000_000L, Duration.ofHours(1)));
        AgentResult<String> r = ajan.run("sonsuza kadar listele", spec);

        assertThat(r.stats().stopReason()).isEqualTo(StopReason.STEP_BUDGET);
        assertThat(r.stats().steps()).isEqualTo(4);
    }

    /**
     * I4: aynı ajan örneği İKİ KEZ çalıştırılır. Eski test ikinci koşu için YENİ bir ajan
     * kuruyordu — bu, çalışma alanını/bütçe sayacını bean üstünde tutan yanlış bir
     * implementasyonu da yeşil geçirirdi. Burada çalışma alanının VE adım sayacının
     * koşular arasında sızmadığı aynı ajan örneğiyle kanıtlanır.
     */
    @Test
    void kosularArasindaCalismaAlaniPAYLASILMAZ() {
        StubChatModel model = new StubChatModel()
                .aracCagir("writeFile", "{\"filePath\":\"/a.md\",\"content\":\"1\"}", 1, 1)
                .metin("bitti", 1, 1)
                .metin("bitti2", 1, 1);
        ZeusAgent ajan = ajan(model);

        AgentResult<String> ilk = ajan.run("yaz", AgentSpec.of("x"));
        assertThat(ilk.workspace()).containsKey("/a.md");
        assertThat(ilk.stats().steps()).isEqualTo(1);

        // AYNI ajan örneği, YENİ koşu: çalışma alanı VE bütçe sayacı taze olmalı.
        AgentResult<String> ikinci = ajan.run("hiçbir şey yapma", AgentSpec.of("x"));
        assertThat(ikinci.workspace()).isEmpty();
        assertThat(ikinci.stats().steps()).isZero();
    }

    @Test
    void uygulamaToollariAjanToollarıylaBirlikteVerilir() {
        StubChatModel model = new StubChatModel().metin("ok", 1, 1);
        ajan(model).run("x", AgentSpec.of("sistem", new SahteUygulamaToolu()));
        // Derleme ve koşunun hatasız tamamlanması yeterli kanıt: uygulama tool'u
        // MethodToolCallbackProvider tarafından reddedilmedi.
        assertThat(model.cagrilar()).hasSize(1);
    }

    @Test
    void runAsMutluYolYapisalCiktiDoner() {
        StubChatModel model = new StubChatModel().metin("{\"ozet\":\"tamam\"}", 10, 5);

        AgentResult<Bulgu> r = ajan(model)
                .runAs("özetle", Bulgu.class, AgentSpec.of("Sen bir ajansın."));

        assertThat(r.output()).isEqualTo(new Bulgu("tamam"));
        assertThat(r.stats().stopReason()).isEqualTo(StopReason.MODEL_FINISHED);
    }

    /**
     * I1: bütçe dolduğunda {@code entity(type)} boş/eksik son içerik üstünde istisna
     * fırlatabilir, ama {@code runAs} bunu "model bitirdi" ile asla karıştırmamalı. ÖLÇÜLEN
     * KUSUR: eski kod catch bloğunda {@code stopReason}'ı KOŞULSUZ {@code ERROR} ile ezardı;
     * burada bütçe sebebinin HAYATTA kaldığı doğrulanır.
     */
    @Test
    void runAsButceDolunca_ERRORDegilButceSebebiKorunur() {
        StubChatModel model = new StubChatModel();
        for (int i = 0; i < 10; i++) {
            model.aracCagir("ls", "{\"path\":\"/\"}", 1, 1);
        }
        AgentSpec spec = new AgentSpec("Sen bir ajansın.", List.of(),
                new AgentBudget(2, 1_000_000L, Duration.ofHours(1)));

        AgentResult<Bulgu> r = ajan(model).runAs("sonsuza kadar listele", Bulgu.class, spec);

        assertThat(r.stats().stopReason()).isEqualTo(StopReason.STEP_BUDGET);
        assertThat(r.output()).isNull();
    }

    /**
     * I5: koşu yarıda bir istisnayla kesilse bile o ana kadar çalışma alanına yazılmış rapor
     * dönmeli ve istisna çağırana SIZMAMALI. Önceden bu akış için hiç test yoktu.
     */
    @Test
    void kismiBasarisizKosu_CalismaAlaniniKorurVeIstisnaSizdirmaz() {
        StubChatModel model = new StubChatModel()
                .aracCagir("writeFile", "{\"filePath\":\"/kismi.md\",\"content\":\"bulgu\"}", 10, 5)
                .hataFirlat();

        AgentResult<String> r = ajan(model).run("araştır", AgentSpec.of("Sen bir ajansın."));

        assertThat(r.stats().stopReason()).isEqualTo(StopReason.ERROR);
        assertThat(r.workspace()).containsEntry("/kismi.md", "bulgu");
    }

    static class SahteUygulamaToolu {
        @org.springframework.ai.tool.annotation.Tool(description = "Deneme amaçlı sahte tool.")
        public String deneme() {
            return "x";
        }
    }
}
