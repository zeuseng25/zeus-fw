package com.zeus.framework.bff;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.function.RequestPredicate;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * Zeus BFF modülü auto-configuration.
 *
 * <p>BFF uygulamaları (parent: {@code zeus-bff-parent}, FAT WAR) iki iş yapar:
 * <ol>
 *   <li><b>Routing</b> — Spring Cloud Gateway Server MVC (servlet). Route'lar uygulamada
 *       property (`spring.cloud.gateway.server.webmvc.routes...`) veya {@code RouterFunction}
 *       bean'leriyle tanımlanır; filter uzantı noktası: {@link ZeusBffFilter}.
 *       DİKKAT (WAR context path'i): gateway path işlemleri context path'i içerir —
 *       ör. {@code StripPrefix} sayısına context segmenti dahildir.</li>
 *   <li><b>SPA sunumu</b> — build alınmış React paketi {@code classpath:/static} altına konur
 *       (WAR'da otomatik sunulur). Bilinmeyen, dosya-uzantısız GET yolları {@code index.html}'e
 *       düşer (SPA fallback) ki tarayıcı yenilemesinde client-side router çalışsın.</li>
 * </ol>
 */
@AutoConfiguration
@EnableConfigurationProperties(ZeusBffProperties.class)
public class ZeusBffAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ZeusBffAutoConfiguration.class);

    public ZeusBffAutoConfiguration() {
        log.info("Zeus BFF modülü yüklendi (Gateway Server MVC + SPA sunumu).");
    }

    /**
     * SPA fallback: uzantısız (statik dosya olmayan) ve dışlanan öneklerle başlamayan GET
     * istekleri {@code classpath:/static/index.html} içeriğiyle yanıtlanır.
     *
     * <p>Dışlama PREDICATE'te yapılır (handler'da DEĞİL): predicate eşleşmezse istek sıradaki
     * {@link RouterFunction}'a (ör. gateway route'ları) devredilir — handler'da 404 dönmek
     * zinciri sonlandırıp gateway'i gölgeleyebilirdi. Uygulama, gateway route öneklerini
     * {@code zeus.bff.spa-fallback.exclude-prefixes} property'sine ekler (varsayılan: /api/).
     * Kapatmak için: {@code zeus.bff.spa-fallback.enabled=false}.
     */
    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    @ConditionalOnMissingBean(name = "zeusBffSpaFallback")
    @ConditionalOnProperty(name = "zeus.bff.spa-fallback.enabled", havingValue = "true", matchIfMissing = true)
    public RouterFunction<ServerResponse> zeusBffSpaFallback(ZeusBffProperties props) {
        ClassPathResource index = new ClassPathResource("static/index.html");
        RequestPredicate spaGet = request -> {
            if (!"GET".equalsIgnoreCase(request.method().name())) {
                return false;
            }
            String path = request.requestPath().pathWithinApplication().value();
            if (path.contains(".")) {
                return false; // uzantılı yollar statik kaynaktır
            }
            return props.getSpaFallback().getExcludePrefixes().stream().noneMatch(path::startsWith);
        };
        return RouterFunctions.route(spaGet,
                request -> ServerResponse.ok().contentType(MediaType.TEXT_HTML).body(index));
    }
}
