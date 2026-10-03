package com.zeus.framework.ai.agent;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Senaryolanmış ChatModel — ajan döngüsünü GERÇEK LLM OLMADAN test etmeyi sağlar.
 *
 * <p>Bu, harness'ın test edilebilirliğinin taşıyıcı koşuludur: bütçe, adım sayımı ve çalışma
 * alanı etkileşimi deterministik olarak sınanamazsa bu katman test edilemez hâle gelir.
 * {@code DefaultZeusAgent}'ın ChatModel'i ENJEKTE ALMASININ sebebi budur.
 */
class StubChatModel implements ChatModel {

    private final Deque<ChatResponse> yanitlar = new ArrayDeque<>();
    private final List<Prompt> cagrilar = new ArrayList<>();

    /** Araç çağıran bir yanıt kuyruğa ekler. */
    StubChatModel aracCagir(String toolAdi, String argumanJson, int promptTok, int completionTok) {
        AssistantMessage msg = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-" + yanitlar.size(), "function", toolAdi, argumanJson)))
                .build();
        yanitlar.add(new ChatResponse(List.of(new Generation(msg)),
                ChatResponseMetadata.builder().usage(new DefaultUsage(promptTok, completionTok)).build()));
        return this;
    }

    /** Düz metin (araç çağırmayan) yanıt kuyruğa ekler. */
    StubChatModel metin(String icerik, int promptTok, int completionTok) {
        yanitlar.add(new ChatResponse(List.of(new Generation(new AssistantMessage(icerik))),
                ChatResponseMetadata.builder().usage(new DefaultUsage(promptTok, completionTok)).build()));
        return this;
    }

    List<Prompt> cagrilar() {
        return cagrilar;
    }

    /**
     * {@code ToolCallingAdvisor.adviseCall}, prompt seçenekleri {@link ToolCallingChatOptions}
     * DEĞİLSE tool döngüsünü (ve dolayısıyla bütçe checker'ını) hiç devreye SOKMAZ — doğrudan
     * alttaki modele geçer. {@code ChatModel.getOptions()}'ın varsayılanı düz {@code ChatOptions}
     * döndürür; bu da döngüyü sessizce devre dışı bırakırdı. Bu ÖLÇÜLDÜ (bkz. task-4-report.md):
     * bu override OLMADAN tüm senaryolarda adım/token sıfırda kalıyor, STEP_BUDGET hiç tetiklenmiyor.
     */
    @Override
    public ChatOptions getOptions() {
        return ToolCallingChatOptions.builder().build();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        cagrilar.add(prompt);
        if (yanitlar.isEmpty()) {
            // Senaryo bittiyse döngüyü kapat; testte "beklenenden fazla çağrı" sessiz kalmasın.
            return new ChatResponse(List.of(new Generation(new AssistantMessage("senaryo bitti"))));
        }
        return yanitlar.poll();
    }
}
