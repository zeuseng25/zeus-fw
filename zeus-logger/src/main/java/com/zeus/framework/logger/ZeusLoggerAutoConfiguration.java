package com.zeus.framework.logger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

/**
 * Zeus Logger modülü auto-configuration.
 *
 * <p>Web uygulamalarında {@link RequestLoggingFilter}'ı bean olarak kaydeder; böylece
 * her istek otomatik loglanır. Uygulama kendi filtresini tanımlarsa devreye girmez.
 */
@AutoConfiguration
public class ZeusLoggerAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ZeusLoggerAutoConfiguration.class);

    public ZeusLoggerAutoConfiguration() {
        log.info("Zeus Logger modülü yüklendi.");
    }

    @Bean
    @ConditionalOnWebApplication
    @ConditionalOnMissingBean
    public RequestLoggingFilter zeusRequestLoggingFilter() {
        return new RequestLoggingFilter();
    }
}
