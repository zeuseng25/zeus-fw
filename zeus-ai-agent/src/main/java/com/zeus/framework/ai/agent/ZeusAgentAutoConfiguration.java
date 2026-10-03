package com.zeus.framework.ai.agent;

import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Zeus AI Agent auto-configuration.
 *
 * <p>Sınıf adları {@code afterName} ile STRING olarak verilir: modül Spring AI'ın
 * {@code *.autoconfigure} paketlerine derleme bağımlılığı TAŞIMAZ (zeus-ai ve zeus-ai-mcp ile
 * aynı disiplin).
 */
@AutoConfiguration(afterName = {
        "org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration",
        "org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration"
})
@ConditionalOnClass(ChatModel.class)
// matchIfMissing YOK: yetenekler OPT-IN'dir.
@ConditionalOnProperty(prefix = "zeus.ai.agent", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(ZeusAgentProperties.class)
public class ZeusAgentAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ZeusAgentAutoConfiguration.class);

    public ZeusAgentAutoConfiguration(ZeusAgentProperties properties) {
        log.info("Zeus AI Agent modülü yüklendi — varsayılan bütçe: {} adım / {} token / {}.",
                properties.getMaxSteps(), properties.getMaxTokens(), properties.getMaxDuration());
    }

    @Bean
    @ConditionalOnBean(ChatModel.class)
    @ConditionalOnMissingBean
    public ZeusAgent zeusAgent(ChatModel chatModel,
                               ObjectProvider<ObservationRegistry> observationRegistry) {
        return new DefaultZeusAgent(chatModel,
                observationRegistry.getIfAvailable(() -> ObservationRegistry.NOOP));
    }
}
