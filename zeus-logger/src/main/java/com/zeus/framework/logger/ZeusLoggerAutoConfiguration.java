package com.zeus.framework.logger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * Zeus Logger modülü auto-configuration.
 *
 * <p>Web uygulamalarında iki filtre kaydeder:
 * <ol>
 *   <li>{@link CorrelationIdFilter} — isteğin correlation ID'sini kurar. Zincirde EN ÖNDE
 *       olmalıdır ki sonraki her log satırı (kendi özet satırımız dahil) kimliği taşısın.</li>
 *   <li>{@link RequestLoggingFilter} — isteğin özet satırını basar.</li>
 * </ol>
 * Uygulama kendi filtresini tanımlarsa ilgili bean devreye girmez.
 */
@AutoConfiguration
public class ZeusLoggerAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ZeusLoggerAutoConfiguration.class);

    public ZeusLoggerAutoConfiguration() {
        log.info("Zeus Logger modülü yüklendi.");
    }

    /**
     * Sıra {@code HIGHEST_PRECEDENCE}: correlation ID, diğer tüm filtrelerden ve
     * {@link RequestLoggingFilter}'dan önce set edilmelidir.
     */
    @Bean
    @ConditionalOnWebApplication
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "zeus.correlation", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public CorrelationIdFilter zeusCorrelationIdFilter() {
        return new CorrelationIdFilter();
    }

    @Bean
    @ConditionalOnWebApplication
    @ConditionalOnMissingBean
    @Order(Ordered.HIGHEST_PRECEDENCE + 10)
    public RequestLoggingFilter zeusRequestLoggingFilter() {
        return new RequestLoggingFilter();
    }
}
