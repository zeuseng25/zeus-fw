package com.zeus.framework.database;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.jdbc.datasource.lookup.JndiDataSourceLookup;

/**
 * "WildFly JNDI datasource'u erişilebilir mi?" koşulu — ortamı UYGULAMA AYARI olmadan tespit eder.
 *
 * <p>Birincil (Oracle) JNDI adını lookup etmeyi dener: başarılıysa (WildFly'a deploy) JNDI modu,
 * başarısızsa (lokal embedded, JNDI yok) lokal mod aktif olur. Böylece uygulama tarafında
 * hiçbir datasource/mode property'si gerekmez.
 */
public class OnZeusJndiCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String jndi = context.getEnvironment()
                .getProperty("zeus.database.oracle-jndi", ZeusDatabaseProperties.DEFAULT_ORACLE_JNDI);
        try {
            new JndiDataSourceLookup().getDataSource(jndi);
            return true;
        } catch (RuntimeException notAvailable) {
            return false;
        }
    }
}