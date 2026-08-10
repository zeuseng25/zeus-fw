package com.zeus.framework.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

/**
 * {@link ZeusAiAssistant}'ın Spring AI {@code ChatClient} tabanlı varsayılan implementasyonu.
 *
 * <p>Framework'ün kattığı üç şey:
 * <ul>
 *   <li>Sağlayıcı istisnaları tek tip {@link ZeusAiException}'a sarılır (→ ProblemDetail 502).</li>
 *   <li>Her çağrı süre + token tüketimiyle loglanır (maliyet/gecikme izlenebilsin).</li>
 *   <li>Tool listesi boşsa {@code tools(...)} hiç çağrılmaz — gereksiz boş tool tanımı gitmez.</li>
 * </ul>
 */
public class DefaultZeusAiAssistant implements ZeusAiAssistant {

    private static final Logger log = LoggerFactory.getLogger(DefaultZeusAiAssistant.class);

    private final ChatClient chatClient;

    public DefaultZeusAiAssistant(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public String ask(String userText, Object... tools) {
        long start = System.nanoTime();
        try {
            ChatResponse response = request(userText, tools).call().chatResponse();
            if (response == null || response.getResult() == null) {
                throw new ZeusAiException("Model boş yanıt döndürdü.");
            }
            logCall("ask", start, response);
            return response.getResult().getOutput().getText();
        } catch (ZeusAiException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ZeusAiException("Model çağrısı başarısız: " + e.getMessage(), e);
        }
    }

    @Override
    public <T> T askAs(String userText, Class<T> type, Object... tools) {
        long start = System.nanoTime();
        try {
            // responseEntity: hem dönüştürülmüş nesneyi hem ham ChatResponse'u verir
            // (token metriklerini kaybetmeden yapılandırılmış çıktı almak için).
            ResponseEntity<ChatResponse, T> result = request(userText, tools)
                    .call()
                    .responseEntity(type);
            if (result == null || result.entity() == null) {
                throw new ZeusAiException(
                        "Model yanıtı %s tipine dönüştürülemedi.".formatted(type.getSimpleName()));
            }
            logCall("askAs(" + type.getSimpleName() + ")", start, result.response());
            return result.entity();
        } catch (ZeusAiException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ZeusAiException("Model çağrısı başarısız: " + e.getMessage(), e);
        }
    }

    /** Ortak istek kurulumu; tool verilmediyse tools(...) çağrılmaz. */
    private ChatClient.ChatClientRequestSpec request(String userText, Object... tools) {
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt().user(userText);
        if (tools != null && tools.length > 0) {
            spec = spec.tools(tools);
        }
        return spec;
    }

    private void logCall(String operation, long startNanos, ChatResponse response) {
        long millis = (System.nanoTime() - startNanos) / 1_000_000;
        Usage usage = (response != null && response.getMetadata() != null)
                ? response.getMetadata().getUsage() : null;
        if (usage != null) {
            log.info("AI {} tamamlandı — {} ms, token: giriş={} çıkış={} toplam={}",
                    operation, millis, usage.getPromptTokens(),
                    usage.getCompletionTokens(), usage.getTotalTokens());
        } else {
            log.info("AI {} tamamlandı — {} ms (token bilgisi sağlayıcıdan gelmedi)",
                    operation, millis);
        }
    }
}
