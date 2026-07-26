package com.zeus.framework.database;

import org.springframework.jdbc.datasource.lookup.JndiDataSourceLookup;

import javax.sql.DataSource;

/**
 * WildFly (veya başka JNDI sağlayıcı) üzerinde tanımlı datasource'lara erişim yardımcısı.
 *
 * <p>Spring Boot'un {@code spring.datasource.jndi-name} property'si yalnız <b>tek</b> datasource
 * kurar. Birden fazla JNDI datasource gerektiğinde, her biri kod ile {@link #jndi(String)} çağrılarak
 * bir {@code @Bean} olarak tanımlanır; böylece geliştirici her {@code StoredProcedureExecutor}'ı
 * istediği datasource'a {@code @Qualifier} ile bağlayabilir.
 *
 * <p>Detay ve reçete: {@code zeus-fw/gelistirmeler/03-zeus-database.md} (çoklu datasource).
 */
public final class ZeusDataSources {

    private static final JndiDataSourceLookup LOOKUP = new JndiDataSourceLookup();

    private ZeusDataSources() {
    }

    /**
     * Verilen JNDI adındaki datasource'u döndürür
     * (ör. {@code java:jboss/datasources/ReportingDS}).
     */
    public static DataSource jndi(String jndiName) {
        return LOOKUP.getDataSource(jndiName);
    }
}