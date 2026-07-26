package com.zeus.framework.base;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

/**
 * Zeus Base modülü auto-configuration.
 *
 * <p>Web uygulamalarında ortak {@link GlobalExceptionHandler}'ı bean olarak kaydeder
 * (uygulama kendi handler'ını tanımlamadıysa). Böylece istisnalar RFC 7807 ProblemDetail
 * olarak döner — uygulamanın bileşen taraması yapmasına gerek kalmaz.
 */
@AutoConfiguration
public class ZeusBaseAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ZeusBaseAutoConfiguration.class);

    public ZeusBaseAutoConfiguration() {
        log.info("Zeus Base modülü yüklendi.");
    }

    @Bean
    @ConditionalOnWebApplication
    @ConditionalOnMissingBean
    public GlobalExceptionHandler zeusGlobalExceptionHandler() {
        return new GlobalExceptionHandler();
    }
}
