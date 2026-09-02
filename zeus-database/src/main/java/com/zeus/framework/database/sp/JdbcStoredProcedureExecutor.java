package com.zeus.framework.database.sp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.SimpleJdbcCall;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link StoredProcedureExecutor}'ın Spring JDBC ({@link SimpleJdbcCall}) implementasyonu.
 *
 * <p>Compile edilmiş {@code SimpleJdbcCall} örnekleri katalog+procedure(+cursor) anahtarıyla
 * önbelleğe alınır; böylece her çağrıda metadata sorgusu tekrarlanmaz (örnek başına derleme
 * yalnızca ilk kullanımda olur). {@code SimpleJdbcCall} derlendikten sonra thread-safe'tir.
 */
public class JdbcStoredProcedureExecutor implements StoredProcedureExecutor {

    /**
     * İzleme izi: her stored procedure çağrısı DEBUG seviyesinde loglanır. Satır, aktif
     * correlation ID ile basıldığı için isteğin veri erişim adımı da zincirde görünür
     * ({@code logging.level.com.zeus.framework.database=DEBUG} ile açılır).
     */
    private static final Logger log = LoggerFactory.getLogger(JdbcStoredProcedureExecutor.class);

    private final DataSource dataSource;
    private final Map<String, SimpleJdbcCall> cache = new ConcurrentHashMap<>();

    public JdbcStoredProcedureExecutor(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> List<T> query(String catalogName, String procedureName, String cursorName,
                             RowMapper<T> rowMapper, Map<String, ?> inParams) {
        SimpleJdbcCall call = cache.computeIfAbsent(
                catalogName + "." + procedureName + "#" + cursorName,
                k -> baseCall(catalogName, procedureName).returningResultSet(cursorName, rowMapper));
        log.debug("SP query: {}.{} (cursor={})", catalogName, procedureName, cursorName);
        Map<String, Object> out = call.execute(new MapSqlParameterSource(inParams));
        return (List<T>) out.getOrDefault(cursorName, List.of());
    }

    @Override
    public Map<String, Object> execute(String catalogName, String procedureName, Map<String, ?> inParams) {
        SimpleJdbcCall call = cache.computeIfAbsent(
                catalogName + "." + procedureName,
                k -> baseCall(catalogName, procedureName));
        log.debug("SP execute: {}.{}", catalogName, procedureName);
        return call.execute(new MapSqlParameterSource(inParams));
    }

    private SimpleJdbcCall baseCall(String catalogName, String procedureName) {
        SimpleJdbcCall call = new SimpleJdbcCall(dataSource).withProcedureName(procedureName);
        return catalogName != null ? call.withCatalogName(catalogName) : call;
    }
}
