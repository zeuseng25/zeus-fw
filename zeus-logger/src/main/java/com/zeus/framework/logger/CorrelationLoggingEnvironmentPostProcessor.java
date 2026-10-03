package com.zeus.framework.logger;

import com.zeus.framework.correlation.CorrelationId;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * Correlation ID'nin log satırlarında görünmesi için varsayılan pattern'ı tanımlar.
 *
 * <p>Spring Boot'un konsol/dosya log pattern'ları {@code ${LOG_CORRELATION_PATTERN:-}}
 * yer tutucusunu içerir ve bu {@code logging.pattern.correlation} property'sinden dolar.
 * Buraya değer koymak, uygulamanın kendi {@code logback-spring.xml}'ini yazmasına gerek
 * bırakmaz — framework varsayılanı verir.
 *
 * <p><b>Neden {@code EnvironmentPostProcessor}, auto-configuration değil:</b> loglama
 * altyapısı Spring context'i kurulmadan ÇOK önce (environment hazırlanır hazırlanmaz)
 * başlatılır. Bir {@code @AutoConfiguration} sınıfı bu property'yi set etse çok geç kalırdı
 * ve pattern uygulanmazdı.
 *
 * <p>Property source {@code addLast} ile eklenir → en düşük öncelik. Uygulama kendi
 * {@code logging.pattern.correlation} değerini yazarsa framework varsayılanı ezilir.
 */
public class CorrelationLoggingEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String PROPERTY = "logging.pattern.correlation";

    /** Örnek çıktı: {@code [3f9a1c...] } — kimlik yoksa köşeli parantez içi boş kalır. */
    private static final String DEFAULT_PATTERN = "[%X{" + CorrelationId.MDC_KEY + ":-}] ";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment,
                                       SpringApplication application) {
        environment.getPropertySources().addLast(new MapPropertySource(
                "zeusCorrelationLoggingDefaults", Map.of(PROPERTY, DEFAULT_PATTERN)));
    }
}
