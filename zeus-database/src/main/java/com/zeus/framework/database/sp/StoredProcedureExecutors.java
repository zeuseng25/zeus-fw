package com.zeus.framework.database.sp;

import java.util.function.Supplier;

/**
 * Kurumsal standart datasource'lar için {@link StoredProcedureExecutor} erişimi.
 *
 * <p>Uygulama tek bir bean inject edip istediği veritabanını <b>isimli getter</b> ile seçer:
 * <pre>
 *   private final StoredProcedureExecutors sp;      // tek inject (framework kurar)
 *   sp.getOracleDs().query(...);                    // OracleDS
 *   sp.getReportDs().execute(...);                  // ReportingDS
 * </pre>
 *
 * <p>Executor'lar <b>tembel</b> (lazy) üretilir: bir getter ilk çağrıldığında oluşturulur ve
 * önbelleğe alınır. Böylece ReportingDS'i hiç kullanmayan bir uygulama, o datasource ortamda
 * yoksa bile başlangıçta hata almaz.
 */
public class StoredProcedureExecutors {

    private final Supplier<StoredProcedureExecutor> oracleFactory;
    private final Supplier<StoredProcedureExecutor> reportFactory;

    private volatile StoredProcedureExecutor oracleDs;
    private volatile StoredProcedureExecutor reportDs;

    public StoredProcedureExecutors(Supplier<StoredProcedureExecutor> oracleFactory,
                                    Supplier<StoredProcedureExecutor> reportFactory) {
        this.oracleFactory = oracleFactory;
        this.reportFactory = reportFactory;
    }

    /** Birincil (OracleDS) executor. */
    public StoredProcedureExecutor getOracleDs() {
        StoredProcedureExecutor e = oracleDs;
        if (e == null) {
            synchronized (this) {
                e = oracleDs;
                if (e == null) {
                    e = oracleFactory.get();
                    oracleDs = e;
                }
            }
        }
        return e;
    }

    /** Raporlama (ReportingDS) executor. */
    public StoredProcedureExecutor getReportDs() {
        StoredProcedureExecutor e = reportDs;
        if (e == null) {
            synchronized (this) {
                e = reportDs;
                if (e == null) {
                    e = reportFactory.get();
                    reportDs = e;
                }
            }
        }
        return e;
    }
}