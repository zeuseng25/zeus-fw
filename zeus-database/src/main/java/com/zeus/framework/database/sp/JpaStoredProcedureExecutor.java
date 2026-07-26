package com.zeus.framework.database.sp;

import jakarta.persistence.EntityManager;
import jakarta.persistence.ParameterMode;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.StoredProcedureQuery;

import java.util.List;

/**
 * JPA ({@link EntityManager#createStoredProcedureQuery}) tabanlı stored-procedure yardımcısı.
 *
 * <p>Parametreler PL/SQL imzasındaki sırayla (pozisyonel) register edilir. İki kullanım:
 * <ul>
 *   <li>{@link #query} — son parametresi REF CURSOR olan, sonucu entity'ye maplenen çağrılar.</li>
 *   <li>{@link #executeWithOut} — son parametresi skaler OUT olan çağrılar (ör. üretilen id / satır sayısı).</li>
 * </ul>
 */
public class JpaStoredProcedureExecutor {

    @PersistenceContext
    private EntityManager em;

    public JpaStoredProcedureExecutor() {
    }

    /**
     * REF CURSOR döndüren procedure'ü çağırır. IN parametreleri sırayla register edilir,
     * ardından son pozisyona REF CURSOR eklenir; sonuç {@code resultClass}'a maplenir.
     */
    @SuppressWarnings("unchecked")
    public <T> List<T> query(String procedureName, Class<T> resultClass, Object... inParams) {
        StoredProcedureQuery q = em.createStoredProcedureQuery(procedureName, resultClass);
        int pos = registerIn(q, inParams);
        q.registerStoredProcedureParameter(pos, void.class, ParameterMode.REF_CURSOR);
        q.execute();
        return q.getResultList();
    }

    /**
     * Son parametresi skaler OUT olan procedure'ü çağırır ve OUT değerini döndürür.
     *
     * @param outType OUT parametre tipi (ör. {@code Long.class})
     */
    public Object executeWithOut(String procedureName, Class<?> outType, Object... inParams) {
        StoredProcedureQuery q = em.createStoredProcedureQuery(procedureName);
        int pos = registerIn(q, inParams);
        q.registerStoredProcedureParameter(pos, outType, ParameterMode.OUT);
        q.execute();
        return q.getOutputParameterValue(pos);
    }

    /** IN parametrelerini 1'den başlayarak register/set eder; bir sonraki boş pozisyonu döndürür. */
    private int registerIn(StoredProcedureQuery q, Object... inParams) {
        int pos = 1;
        for (Object p : inParams) {
            q.registerStoredProcedureParameter(pos, p != null ? p.getClass() : Object.class, ParameterMode.IN);
            q.setParameter(pos, p);
            pos++;
        }
        return pos;
    }
}
