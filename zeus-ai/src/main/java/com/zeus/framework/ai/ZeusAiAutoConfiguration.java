package com.zeus.framework.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Zeus AI modülü auto-configuration.
 *
 * <p>Spring AI'ın kendi auto-config'lerinden SONRA çalışır: model (OpenAI-uyumlu endpoint)
 * ve {@code ChatClient.Builder} onlar tarafından kurulur, biz üzerine kurumsal varsayılanları
 * (ortak sistem promptu, log advisor'ı) ekleyip {@link ZeusAiAssistant} olarak sunarız.
 *
 * <p>Sınıf adları {@code afterName} ile STRING olarak verilir; böylece zeus-ai, Spring AI'ın
 * auto-configure paketlerine derleme zamanı bağımlılığı taşımaz (sürüm/paket değişikliğine dayanıklı).
 */
@AutoConfiguration(afterName = {
        "org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration",
        "org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration"
})
@ConditionalOnClass(ChatClient.class)
@ConditionalOnProperty(prefix = "zeus.ai", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ZeusAiProperties.class)
public class ZeusAiAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ZeusAiAutoConfiguration.class);

    public ZeusAiAutoConfiguration() {
        log.info("Zeus AI modülü yüklendi.");
    }

    /**
     * Kurumsal varsayılanları uygulanmış tek {@link ChatClient}.
     *
     * <p>Tool döngüsü BURADA kurulmaz: Spring AI 2.0'da {@code ToolCallingAdvisor} otomatik
     * kayıtlıdır ve tool çağrılarının tur döngüsünü kendisi yürütür (1.x'te bu döngü model
     * implementasyonunun içinde gömülüydü, araya girilemiyordu).
     */
    @Bean
    @ConditionalOnBean(ChatClient.Builder.class)
    @ConditionalOnMissingBean
    public ChatClient zeusChatClient(ChatClient.Builder builder, ZeusAiProperties properties) {
        ChatClient.Builder configured = builder.defaultSystem(properties.getSystemPrompt());
        if (properties.isLogConversation()) {
            configured = configured.defaultAdvisors(new SimpleLoggerAdvisor());
        }
        return configured.build();
    }

    @Bean
    @ConditionalOnBean(ChatClient.class)
    @ConditionalOnMissingBean
    public ZeusAiAssistant zeusAiAssistant(ChatClient chatClient) {
        return new DefaultZeusAiAssistant(chatClient);
    }

    @Bean
    @ConditionalOnWebApplication
    @ConditionalOnMissingBean
    public ZeusAiExceptionHandler zeusAiExceptionHandler() {
        return new ZeusAiExceptionHandler();
    }
}
