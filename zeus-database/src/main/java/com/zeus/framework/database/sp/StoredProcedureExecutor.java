package com.zeus.framework.database.sp;

import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Map;

/**
 * Spring JDBC ile Oracle (veya herhangi bir) stored procedure çağırmak için ortak sözleşme.
 *
 * <p>Uygulamalar her procedure için elle {@code SimpleJdbcCall} kurmak yerine bu yardımcıyı
 * kullanır. İki temel kullanım:
 * <ul>
 *   <li>{@link #query} — REF CURSOR döndüren procedure'ler; sonuç {@link RowMapper} ile maplenir.</li>
 *   <li>{@link #execute} — OUT parametreleri döndüren procedure'ler; OUT değerleri map olarak döner.</li>
 * </ul>
 *
 * <p>Parametre adları PL/SQL imzasındaki adlarla eşleşmelidir (Oracle metadata'sından çözülür).
 */
public interface StoredProcedureExecutor {

    /**
     * REF CURSOR döndüren bir procedure'ü çağırır.
     *
     * @param catalogName   paket/katalog adı (ör. {@code "PRODUCT_PKG"}); paketsizse {@code null}
     * @param procedureName procedure adı (ör. {@code "GET_BY_ID"})
     * @param cursorName    REF CURSOR OUT parametresinin adı (ör. {@code "P_CUR"})
     * @param rowMapper     her satırı hedef tipe çeviren mapper
     * @param inParams      IN parametreleri (ad → değer); yoksa boş map
     */
    <T> List<T> query(String catalogName, String procedureName, String cursorName,
                      RowMapper<T> rowMapper, Map<String, ?> inParams);

    /**
     * OUT parametre(ler)i olan bir procedure'ü çağırır ve tüm OUT değerlerini döndürür.
     *
     * @return OUT parametre adı → değer map'i (ör. {@code P_ID}, {@code P_ROWS})
     */
    Map<String, Object> execute(String catalogName, String procedureName, Map<String, ?> inParams);

    /** IN parametresi olmayan REF CURSOR çağrısı için kısayol. */
    default <T> List<T> query(String catalogName, String procedureName, String cursorName,
                              RowMapper<T> rowMapper) {
        return query(catalogName, procedureName, cursorName, rowMapper, Map.of());
    }

    /** IN parametresi olmayan OUT çağrısı için kısayol. */
    default Map<String, Object> execute(String catalogName, String procedureName) {
        return execute(catalogName, procedureName, Map.of());
    }
}
