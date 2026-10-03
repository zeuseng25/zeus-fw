package com.zeus.framework.sms;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * SMS istemcisi auto-configuration'ı.
 *
 * <p>{@code zeus.sms.endpoint} tanımlı DEĞİLSE hiçbir bean kurulmaz. Bu bilinçlidir: paylaşımlı
 * module modeli yüzünden bir uygulama kullanmadığı yetenekleri de classpath'inde görür; endpoint
 * koşulu, SMS kullanmayan uygulamanın CXF'e hiç dokunmamasını sağlar.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "zeus.sms", name = "endpoint")
@EnableConfigurationProperties(ZeusSmsProperties.class)
public class ZeusSmsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ZeusSmsClient zeusSmsClient(ZeusSmsProperties props) {
        return new ZeusSmsClient(props);
    }
}
