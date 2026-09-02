package com.zeus.framework.boot;

import com.zeus.framework.correlation.CorrelationId;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * WAR olarak deploy edilen zeus uygulamalarının başlatıcısı.
 *
 * <p>Uygulamalar {@code SpringBootServletInitializer} yerine bunu genişletir; böylece
 * framework, Spring context kurulmadan ÖNCE geçerli olması gereken varsayılanları
 * uygulamaya enjekte edebilir.
 *
 * <pre>
 * public class FooApplication extends ZeusServletInitializer { ... }
 * </pre>
 *
 * <h2>Neden bu sınıf var — WildFly module classloader kısıtı</h2>
 *
 * Bu tür varsayılanların normal yolu bir {@code EnvironmentPostProcessor}'dır
 * ({@code CorrelationLoggingEnvironmentPostProcessor}) ve gömülü çalıştırmada
 * ({@code spring-boot:run}) sorunsuz çalışır. Ancak ince WAR modelinde {@code spring-boot}
 * jar'ı paylaşımlı {@code com.zeus} WildFly module'ündedir ve o classloader WAR'ın
 * {@code WEB-INF/lib}'indeki {@code META-INF/spring.factories} dosyalarını <b>göremez</b> —
 * dolayısıyla EnvironmentPostProcessor WildFly'da hiç çalışmaz. (Auto-configuration'ın
 * {@code .imports} dosyaları etkilenmez; onlar uygulama classloader'ı ile yüklenir.)
 *
 * <p>Bu sınıf WAR'ın kendi sınıf hiyerarşisinde olduğu için o kısıttan etkilenmez:
 * {@code SpringApplicationBuilder.properties(...)} ile eklenen değerler environment'a
 * loglama altyapısı başlatılmadan önce girer ve pattern uygulanır.
 *
 * <p>Değerler <b>varsayılan</b> olarak eklenir (en düşük öncelik): uygulama kendi
 * {@code application.properties}'inde aynı anahtarı yazarsa onunki geçerli olur.
 */
public abstract class ZeusServletInitializer extends SpringBootServletInitializer {

    @Override
    protected SpringApplicationBuilder createSpringApplicationBuilder() {
        return super.createSpringApplicationBuilder().properties(zeusDefaultProperties());
    }

    /**
     * Framework varsayılanları. Alt sınıflar {@code super}'ı çağırıp ekleme yapabilir.
     */
    protected Map<String, Object> zeusDefaultProperties() {
        Map<String, Object> defaults = new LinkedHashMap<>();
        // Correlation ID'yi Spring Boot'un standart log pattern'ındaki yuvaya yerleştirir
        // (LOG_CORRELATION_PATTERN). Böylece isteğin tüm aşamaları aynı kimlikle basılır.
        defaults.put("logging.pattern.correlation", "[%X{" + CorrelationId.MDC_KEY + ":-}] ");
        return defaults;
    }
}
