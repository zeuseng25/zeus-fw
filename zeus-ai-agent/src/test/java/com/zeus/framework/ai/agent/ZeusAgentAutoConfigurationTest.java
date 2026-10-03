package com.zeus.framework.ai.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in simetrisinin kanıtı — {@code ZeusSoapAutoConfigurationTest} / {@code
 * ZeusMcpAutoConfigurationTest} şablonu. Framework'ün kendi hata mesajı
 * {@code zeus.<yetenek>.enabled=false} yazmayı önerdiği için, o cümle açılışı çökertmemeli.
 */
class ZeusAgentAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ZeusAgentAutoConfiguration.class))
            .withBean(ChatModel.class, () -> new StubChatModel());

    @Test
    void bilincliFalseAcilisiCokertmez() {
        runner.withPropertyValues("zeus.ai.agent.enabled=false")
                .run(ctx -> assertThat(ctx).hasNotFailed().doesNotHaveBean(ZeusAgent.class));
    }

    @Test
    void propertyHicYazilmamissaDaAcilisCokmez() {
        runner.run(ctx -> assertThat(ctx).hasNotFailed().doesNotHaveBean(ZeusAgent.class));
    }

    @Test
    void trueYazilincaAjanKurulur() {
        runner.withPropertyValues("zeus.ai.agent.enabled=true")
                .run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(ZeusAgent.class));
    }

    @Test
    void butceVarsayilanlariPropertyIleDegistirilebilir() {
        runner.withPropertyValues("zeus.ai.agent.enabled=true",
                        "zeus.ai.agent.max-steps=40",
                        "zeus.ai.agent.max-tokens=500000",
                        "zeus.ai.agent.max-duration=10m")
                .run(ctx -> {
                    AgentBudget b = ctx.getBean(ZeusAgentProperties.class).toBudget();
                    assertThat(b.maxSteps()).isEqualTo(40);
                    assertThat(b.maxTokens()).isEqualTo(500_000L);
                    assertThat(b.maxDuration()).isEqualTo(java.time.Duration.ofMinutes(10));
                });
    }

    @Test
    void chatModelYoksaAjanKurulmaz() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ZeusAgentAutoConfiguration.class))
                .withPropertyValues("zeus.ai.agent.enabled=true")
                .run(ctx -> assertThat(ctx).hasNotFailed().doesNotHaveBean(ZeusAgent.class));
    }
}
