package com.zeus.framework.correlation;

import org.slf4j.MDC;

import java.util.Map;
import java.util.UUID;

/**
 * Bir isteğin tüm aşamalarını tek kimlikle izlemeyi sağlayan correlation ID.
 *
 * <p>Taşıyıcı SLF4J {@link MDC}'dir: thread-local bir map olduğu için değer, çağrı zinciri
 * boyunca (controller → service → repository → stored procedure) parametre geçirmeden
 * görünür kalır. Log pattern'ındaki {@code %X{correlationId}} onu otomatik basar; yani
 * modüllerin birbirine bağımlı olmasına gerek yoktur.
 *
 * <p><b>Neden zeus-base?</b> Bu sınıf tüm zeus modüllerinin (database, service, ai, soap,
 * logger) ortak kökündedir. zeus-logger'a konsaydı, correlation ID'yi programatik olarak
 * okuması gereken her modülün web/servlet bağımlılığı olan bir modüle bağlanması gerekirdi.
 *
 * <p>Yaşam döngüsü web uygulamalarında {@code CorrelationIdFilter} (zeus-logger) tarafından
 * yönetilir: isteğin başında set edilir, {@code finally} bloğunda temizlenir. Temizlememek
 * havuzdaki thread'in bir sonraki isteğe yanlış kimlikle girmesine yol açar.
 */
public final class CorrelationId {

    /** MDC anahtarı. Log pattern'ında {@code %X{correlationId}} olarak kullanılır. */
    public static final String MDC_KEY = "correlationId";

    /** Servisler arası taşımada kullanılan HTTP header adı. */
    public static final String HEADER_NAME = "X-Correlation-Id";

    /** Kabul edilen azami uzunluk — daha uzun gelen dış değerler reddedilir. */
    private static final int MAX_LENGTH = 64;

    private CorrelationId() {
    }

    /** Aktif correlation ID; yoksa {@code null}. */
    public static String get() {
        return MDC.get(MDC_KEY);
    }

    /** Değeri MDC'ye yazar. Boş/null değer yok sayılır. */
    public static void set(String correlationId) {
        if (correlationId != null && !correlationId.isBlank()) {
            MDC.put(MDC_KEY, correlationId);
        }
    }

    /** MDC'den siler. İstek bittiğinde MUTLAKA çağrılmalıdır (thread havuzu kirlenmesin). */
    public static void clear() {
        MDC.remove(MDC_KEY);
    }

    /** Yeni bir kimlik üretir (tire'siz UUID — log satırında kısa durur). */
    public static String generate() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** Aktif kimlik varsa onu, yoksa yeni üretip set ederek döner. */
    public static String getOrGenerate() {
        String current = get();
        if (current == null || current.isBlank()) {
            current = generate();
            set(current);
        }
        return current;
    }

    /**
     * Dışarıdan gelen (header'daki) değeri güvenli hale getirir; kabul edilemezse yenisini üretir.
     *
     * <p><b>Güvenlik — log injection.</b> Header değeri istemci kontrolündedir. Doğrudan MDC'ye
     * yazılırsa satır sonu veya ANSI kaçış dizisi içeren bir değer log dosyasına sahte satır
     * enjekte edebilir (bkz. {@code 11-guvenlik-analizi-best-practices.md}, G-4). Bu yüzden
     * yalnızca {@code [A-Za-z0-9._-]} karakterleri ve en fazla {@value #MAX_LENGTH} uzunluk
     * kabul edilir; ihlal eden değer sessizce atılıp yerine yeni kimlik üretilir.
     */
    public static String sanitizeOrGenerate(String candidate) {
        if (candidate == null || candidate.isBlank() || candidate.length() > MAX_LENGTH) {
            return generate();
        }
        for (int i = 0; i < candidate.length(); i++) {
            char c = candidate.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.';
            if (!allowed) {
                return generate();
            }
        }
        return candidate;
    }

    /** MDC'nin tamamının kopyası — thread'ler arası taşıma için. Boşsa {@code null} dönebilir. */
    public static Map<String, String> capture() {
        return MDC.getCopyOfContextMap();
    }

    /** {@link #capture()} ile alınmış bağlamı geri yükler; {@code null} ise MDC temizlenir. */
    public static void restore(Map<String, String> context) {
        if (context == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(context);
        }
    }
}
