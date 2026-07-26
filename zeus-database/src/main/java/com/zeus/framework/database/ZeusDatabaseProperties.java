package com.zeus.framework.database;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Zeus Database — kurumsal standart datasource'ların JNDI adları ({@code zeus.database.*}).
 *
 * <p>Datasource yönetimi TAMAMEN framework'tedir: JNDI adları burada <b>varsayılan</b> olarak tanımlıdır,
 * uygulamalar bunları bilmez/yazmaz. Bir kurulumda adlar farklıysa yalnız o ortamda override edilir
 * (ör. {@code zeus.database.report-jndi=...}); tipik uygulama hiçbir şey set etmez.
 *
 * <p>Uygulama sadece {@link com.zeus.framework.database.sp.StoredProcedureExecutors}'ı inject edip
 * {@code sp.getOracleDs()} / {@code sp.getReportDs()} çağırır.
 */
@ConfigurationProperties(prefix = "zeus.database")
public class ZeusDatabaseProperties {

    /** Standart birincil (Oracle) datasource JNDI adı. */
    public static final String DEFAULT_ORACLE_JNDI = "java:jboss/datasources/OracleDS";
    /** Standart raporlama datasource JNDI adı. */
    public static final String DEFAULT_REPORT_JNDI = "java:jboss/datasources/ReportingDS";

    private String oracleJndi = DEFAULT_ORACLE_JNDI;
    private String reportJndi = DEFAULT_REPORT_JNDI;

    public String getOracleJndi() {
        return oracleJndi;
    }

    public void setOracleJndi(String oracleJndi) {
        this.oracleJndi = oracleJndi;
    }

    public String getReportJndi() {
        return reportJndi;
    }

    public void setReportJndi(String reportJndi) {
        this.reportJndi = reportJndi;
    }
}