package com.zeus.framework.database;

import com.zeus.framework.database.sp.JdbcStoredProcedureExecutor;
import com.zeus.framework.database.sp.JpaStoredProcedureExecutor;
import com.zeus.framework.database.sp.StoredProcedureExecutor;
import com.zeus.framework.database.sp.StoredProcedureExecutors;
import jakarta.persistence.EntityManagerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Zeus Database modülü auto-configuration.
 *
 * <p>JNDI (WildFly) modu ayrı bir auto-config'tedir: {@link ZeusJndiDataSourceAutoConfiguration}.
 * Burası:
 * <ul>
 *   <li><b>Lokal mod</b> (JNDI erişilemezken, ör. embedded çalıştırma): Spring Boot'un tek
 *       {@link DataSource}'u için bir {@link StoredProcedureExecutors} kurar; her iki getter
 *       ({@code getOracleDs()}/{@code getReportDs()}) o tek datasource'a gider. Böylece uygulama
 *       kodu her iki ortamda aynı API'yi kullanır.</li>
 *   <li>JPA yapılandırılmışsa {@link JpaStoredProcedureExecutor} (tek persistence unit).</li>
 * </ul>
 */
@AutoConfiguration(after = {DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class})
@EnableConfigurationProperties(ZeusDatabaseProperties.class)
public class ZeusDatabaseAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ZeusDatabaseAutoConfiguration.class);

    public ZeusDatabaseAutoConfiguration() {
        log.info("Zeus Database modülü yüklendi.");
    }

    // ---- JPA executor (JPA varsa; her iki modda) ----
    @Bean
    @ConditionalOnClass(EntityManagerFactory.class)
    @ConditionalOnBean(EntityManagerFactory.class)
    @ConditionalOnMissingBean
    public JpaStoredProcedureExecutor zeusJpaStoredProcedureExecutor() {
        return new JpaStoredProcedureExecutor();
    }

    /**
     * Lokal mod — JNDI erişilemezken (JNDI modu {@link OnZeusJndiCondition} ile eşleşmemiş).
     * Spring Boot'un ambient DataSource'unu her iki datasource getter'ına bağlar.
     */
    @Configuration(proxyBeanMethods = false)
    @Conditional(NotWildFlyJndiCondition.class)
    static class LocalDataSourceConfig {

        @Bean
        @ConditionalOnSingleCandidate(DataSource.class)
        @ConditionalOnMissingBean
        StoredProcedureExecutors zeusStoredProcedureExecutors(DataSource dataSource) {
            StoredProcedureExecutor exec = new JdbcStoredProcedureExecutor(dataSource);
            // Lokalde tek DB var → her iki getter aynı executor'a gider.
            return new StoredProcedureExecutors(() -> exec, () -> exec);
        }
    }

    /** {@link OnZeusJndiCondition}'ın olumsuzu (lokal mod için). */
    static class NotWildFlyJndiCondition extends OnZeusJndiCondition {
        @Override
        public boolean matches(org.springframework.context.annotation.ConditionContext context,
                               org.springframework.core.type.AnnotatedTypeMetadata metadata) {
            return !super.matches(context, metadata);
        }
    }
}
