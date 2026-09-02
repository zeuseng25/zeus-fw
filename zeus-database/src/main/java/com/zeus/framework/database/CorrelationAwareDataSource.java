package com.zeus.framework.database;

import com.zeus.framework.correlation.CorrelationId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Alınan her bağlantıya aktif correlation ID'yi damgalar; izlemeyi veritabanına taşır.
 *
 * <p><b>Kazanç:</b> DBA, {@code v$session.client_identifier} (ve {@code v$active_session_history})
 * üzerinden bir yavaş sorgunun hangi HTTP isteğinden geldiğini görebilir. Uygulama logu ile
 * veritabanı tarafı aynı kimlikle birleşir — "şu istek yavaştı" ile "şu sorgu yavaştı"
 * arasındaki boşluk kapanır.
 *
 * <pre>
 * SELECT sid, client_identifier, sql_id FROM v$session WHERE client_identifier = '&lt;correlationId&gt;';
 * </pre>
 *
 * <p><b>Oracle'a kod bağımlılığı yoktur:</b> damgalama JDBC standardı olan
 * {@link Connection#setClientInfo(String, String)} ile yapılır. Oracle sürücüsü
 * {@code OCSID.CLIENTID} anahtarını {@code client_identifier}'a eşler. Başka bir veritabanı
 * bu anahtarı tanımazsa çağrı sessizce yok sayılır (aşağıya bakınız) — modül veritabanı
 * bağımsız kalır ve {@code import oracle.*} içermez.
 *
 * <p><b>Hata politikası:</b> damgalama <i>en iyi çaba</i>dır. Sürücü desteklemiyorsa ya da
 * havuz izin vermiyorse istisna yutulur ve <b>bir kez</b> uyarı loglanır; veri erişimi asla
 * bu yüzden kırılmaz.
 */
public class CorrelationAwareDataSource extends DelegatingDataSource {

    /** Oracle JDBC sürücüsünün {@code v$session.client_identifier}'a eşlediği anahtar. */
    static final String ORACLE_CLIENT_IDENTIFIER = "OCSID.CLIENTID";

    private static final Logger log = LoggerFactory.getLogger(CorrelationAwareDataSource.class);

    /** Uyarı log'u yalnızca ilk başarısızlıkta basılır (her sorguda gürültü yapmasın). */
    private final AtomicBoolean warned = new AtomicBoolean(false);

    public CorrelationAwareDataSource(DataSource targetDataSource) {
        super(targetDataSource);
    }

    /**
     * Gerekliyse sarar; zaten sarılmışsa ya da özellik kapalıysa olduğu gibi döner.
     *
     * <p>Bean olarak tanımlanan datasource'lar {@link ZeusCorrelationDataSourceAutoConfiguration}
     * içindeki {@code BeanPostProcessor} ile sarılır. Ancak framework, JNDI modunda
     * {@code StoredProcedureExecutors} için datasource'ları <b>bean olmadan</b>
     * ({@link ZeusDataSources#jndi(String)} ile) kurar; o yol BeanPostProcessor'dan geçmez ve
     * bu metotla açıkça sarılmalıdır. Aksi halde uygulamanın asıl sorgu yolu
     * ({@code sp.getOracleDs()}) damgasız kalır.
     */
    public static DataSource wrapIfNeeded(DataSource dataSource, boolean enabled) {
        if (!enabled || dataSource == null || dataSource instanceof CorrelationAwareDataSource) {
            return dataSource;
        }
        return new CorrelationAwareDataSource(dataSource);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return stamp(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return stamp(super.getConnection(username, password));
    }

    private Connection stamp(Connection connection) {
        String correlationId = CorrelationId.get();
        if (correlationId != null && connection != null) {
            try {
                connection.setClientInfo(ORACLE_CLIENT_IDENTIFIER, correlationId);
            } catch (Exception ex) {
                // Sürücü/havuz desteklemiyor olabilir — veri erişimini ASLA kırma.
                if (warned.compareAndSet(false, true)) {
                    log.warn("Correlation ID veritabanı oturumuna yazılamadı ({}). "
                            + "İzleme yalnızca uygulama loglarında sürecek. Sebep: {}",
                            ORACLE_CLIENT_IDENTIFIER, ex.toString());
                }
            }
        }
        return connection;
    }
}
