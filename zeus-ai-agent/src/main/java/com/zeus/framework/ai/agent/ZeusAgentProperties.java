package com.zeus.framework.ai.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Zeus AI Agent ayarları ({@code zeus.ai.agent.*}). */
@ConfigurationProperties(prefix = "zeus.ai.agent")
public class ZeusAgentProperties {

    /**
     * Yeteneğin açık olup olmadığı.
     *
     * <p>Bu alan OKUNMAZ; gerçek kapı {@code @ConditionalOnProperty}'dir. Java varsayılanı
     * bilinçli {@code false} — zeus-ai'deki {@code true} varsayılanı dokümanla çelişen ölü koddu.
     *
     * <p><b>DİKKAT:</b> bu modül 3. parti autoconfig getirmediği için {@code ZeusCapabilities}'e
     * kayıtlı DEĞİLDİR; dolayısıyla jar'ı ekleyip bu property'yi yazmayan uygulama açılışta
     * UYARI ALMAZ, yalnız {@code ZeusAgent} bean'i kurulmaz.
     */
    private boolean enabled = false;

    /** Varsayılan adım sınırı. */
    private int maxSteps = 15;

    /** Varsayılan token sınırı. */
    private long maxTokens = 200_000L;

    /** Varsayılan süre sınırı. Senkron koşuda çağıranın HTTP timeout'undan KÜÇÜK olmalıdır. */
    private Duration maxDuration = Duration.ofMinutes(3);

    /** Property'lerden varsayılan bütçeyi kurar. */
    public AgentBudget toBudget() {
        return new AgentBudget(maxSteps, maxTokens, maxDuration);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxSteps() {
        return maxSteps;
    }

    public void setMaxSteps(int maxSteps) {
        this.maxSteps = maxSteps;
    }

    public long getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(long maxTokens) {
        this.maxTokens = maxTokens;
    }

    public Duration getMaxDuration() {
        return maxDuration;
    }

    public void setMaxDuration(Duration maxDuration) {
        this.maxDuration = maxDuration;
    }
}
