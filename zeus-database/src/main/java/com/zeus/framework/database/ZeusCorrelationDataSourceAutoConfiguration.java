package com.zeus.framework.database;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;

/**
 * {@link DataSource} bean'lerini {@link CorrelationAwareDataSource} ile sarar.
 *
 * <p>Sarma işi bir {@link BeanPostProcessor} ile yapılır; böylece datasource'un nereden
 * geldiği (JNDI, Hikari, test H2) fark etmez ve uygulamaların yapılandırmasına dokunulmaz.
 *
 * <p><b>Kapatma:</b> {@code zeus.correlation.datasource.enabled=false}. JTA/XA kurulumunda
 * beklenmedik bir davranış görülürse ilk müdahale bu property'dir; correlation ID uygulama
 * loglarında çalışmaya devam eder, yalnızca veritabanı oturumu damgası kaybolur.
 */
@AutoConfiguration
@ConditionalOnClass(DataSource.class)
@ConditionalOnProperty(prefix = "zeus.correlation.datasource", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class ZeusCorrelationDataSourceAutoConfiguration {

    private static final Logger log =
            LoggerFactory.getLogger(ZeusCorrelationDataSourceAutoConfiguration.class);

    @Bean
    public static BeanPostProcessor zeusCorrelationDataSourceBeanPostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                // Zaten sarılmışsa tekrar sarma (çift damgalama / sonsuz zincir olmasın).
                if (bean instanceof DataSource dataSource
                        && !(bean instanceof CorrelationAwareDataSource)) {
                    log.debug("DataSource '{}' correlation damgası için sarıldı.", beanName);
                    return new CorrelationAwareDataSource(dataSource);
                }
                return bean;
            }
        };
    }
}
