package com.zeus.framework.database;

import com.zeus.framework.database.sp.JdbcStoredProcedureExecutor;
import com.zeus.framework.database.sp.StoredProcedureExecutors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/**
 * WildFly (JNDI) datasource auto-configuration — datasource'lar tamamen framework'te.
 *
 * <p>Birincil (Oracle) JNDI datasource'u erişilebiliyorsa (WildFly'a deploy) aktif olur
 * ({@link OnZeusJndiCondition}). Standart datasource'ları JNDI'dan bağlar ve isimli erişim için
 * bir {@link StoredProcedureExecutors} kaydeder. Uygulamada datasource kodu/property'si YOKTUR.
 *
 * <p><b>Sıralama:</b> {@code before = DataSourceAutoConfiguration}. Birincil {@link DataSource}
 * Spring Boot'tan ÖNCE (JNDI'dan) kaydedilir → Spring Boot kendi (url'siz) datasource'unu kurmaya
 * çalışmaz ("suitable driver" hatası önlenir), JPA bu {@code @Primary} datasource'u kullanır.
 */
@AutoConfiguration(before = DataSourceAutoConfiguration.class)
@Conditional(OnZeusJndiCondition.class)
@EnableConfigurationProperties(ZeusDatabaseProperties.class)
public class ZeusJndiDataSourceAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ZeusJndiDataSourceAutoConfiguration.class);

    private final ZeusDatabaseProperties props;

    /** Correlation ID'nin veritabanı oturumuna damgalanıp damgalanmayacağı. */
    private final boolean correlationStamp;

    public ZeusJndiDataSourceAutoConfiguration(
            ZeusDatabaseProperties props,
            @Value("${zeus.correlation.enabled:true}") boolean correlationEnabled,
            @Value("${zeus.correlation.datasource.enabled:true}") boolean datasourceStampEnabled) {
        this.props = props;
        this.correlationStamp = correlationEnabled && datasourceStampEnabled;
        log.info("Zeus Database: JNDI datasource modu (oracle={}, report={}, correlation damgası={}).",
                props.getOracleJndi(), props.getReportJndi(), correlationStamp);
    }

    /** JPA/Spring Boot'un ve qualifier'sız DataSource enjeksiyonlarının kullandığı birincil datasource. */
    @Bean
    @Primary
    @ConditionalOnMissingBean(DataSource.class)
    public DataSource zeusPrimaryDataSource() {
        return ZeusDataSources.jndi(props.getOracleJndi());
    }

    /** İsimli executor erişimi — {@code sp.getOracleDs()} / {@code sp.getReportDs()} (lazy JNDI). */
    @Bean
    @ConditionalOnMissingBean
    public StoredProcedureExecutors zeusStoredProcedureExecutors() {
        // DİKKAT: buradaki datasource'lar Spring bean'i DEĞİLDİR (JNDI'dan doğrudan kurulur),
        // dolayısıyla correlation BeanPostProcessor'ından geçmezler — açıkça sarılmaları gerekir.
        return new StoredProcedureExecutors(
                () -> new JdbcStoredProcedureExecutor(jndiDataSource(props.getOracleJndi())),
                () -> new JdbcStoredProcedureExecutor(jndiDataSource(props.getReportJndi())));
    }

    private DataSource jndiDataSource(String jndiName) {
        return CorrelationAwareDataSource.wrapIfNeeded(ZeusDataSources.jndi(jndiName), correlationStamp);
    }
}