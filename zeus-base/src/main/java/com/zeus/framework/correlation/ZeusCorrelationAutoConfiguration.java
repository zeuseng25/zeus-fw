package com.zeus.framework.correlation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.boot.restclient.RestTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Correlation ID altyapısının uygulamadan bağımsız parçalarını kaydeder.
 *
 * <p>Burada <b>web'e bağlı olmayan</b> bileşenler vardır (zeus-base'in servlet bağımlılığı
 * yoktur): thread taşıyıcı ve giden HTTP interceptor'ı. Gelen isteği yakalayan filtre
 * zeus-logger'dadır.
 *
 * <p>Tamamı {@code zeus.correlation.enabled=false} ile kapatılabilir.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "zeus.correlation", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class ZeusCorrelationAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ZeusCorrelationAutoConfiguration.class);

    public ZeusCorrelationAutoConfiguration() {
        log.info("Zeus Correlation: MDC anahtarı '{}', header '{}'.",
                CorrelationId.MDC_KEY, CorrelationId.HEADER_NAME);
    }

    /**
     * Boot'un otomatik yapılandırdığı {@code TaskExecutor}'a takılır
     * ({@code TaskExecutionAutoConfiguration} mevcut {@code TaskDecorator} bean'ini kullanır),
     * böylece {@code @Async} işlerinde correlation ID korunur.
     */
    @Bean
    @ConditionalOnMissingBean
    public CorrelationIdTaskDecorator zeusCorrelationIdTaskDecorator() {
        return new CorrelationIdTaskDecorator();
    }

    @Bean
    @ConditionalOnMissingBean
    public CorrelationIdClientHttpRequestInterceptor zeusCorrelationIdClientHttpRequestInterceptor() {
        return new CorrelationIdClientHttpRequestInterceptor();
    }

    /**
     * RestClient/RestTemplate builder'larına interceptor'ı otomatik ekler.
     *
     * <p>Ayrı bir {@code @Configuration} sınıfında olmasının sebebi {@link ConditionalOnClass}'ın
     * güvenli çalışması: {@code spring-boot-restclient} classpath'te yoksa bu sınıfın metot
     * imzaları hiç yüklenmez (zeus-base'de o bağımlılık {@code optional}'dır).
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(RestClientCustomizer.class)
    static class RestClientConfiguration {

        @Bean
        @ConditionalOnMissingBean(name = "zeusCorrelationRestClientCustomizer")
        RestClientCustomizer zeusCorrelationRestClientCustomizer(
                CorrelationIdClientHttpRequestInterceptor interceptor) {
            return builder -> builder.requestInterceptor(interceptor);
        }

        @Bean
        @ConditionalOnMissingBean(name = "zeusCorrelationRestTemplateCustomizer")
        RestTemplateCustomizer zeusCorrelationRestTemplateCustomizer(
                CorrelationIdClientHttpRequestInterceptor interceptor) {
            return restTemplate -> restTemplate.getInterceptors().add(interceptor);
        }
    }
}
