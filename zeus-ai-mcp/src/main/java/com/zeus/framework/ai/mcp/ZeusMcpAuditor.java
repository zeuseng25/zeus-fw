package com.zeus.framework.ai.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MCP tool çağrılarının audit kanalı.
 *
 * <p><b>Correlation ID burada ELLE taşınmaz.</b> {@code CorrelationIdFilter} (zeus-logger) isteğin
 * başında kimliği SLF4J MDC'sine koyuyor ve log pattern'ı {@code %X{correlationId}} ile her satıra
 * basıyor. Tool gövdesi istek thread'inde çalıştığı sürece audit satırı o kimliği kendiliğinden
 * taşır — bu yüzden bu sınıfın zeus-logger'a bağımlılığı yoktur.
 *
 * <p><b>{@code thread} alanı süs değil.</b> Tool gövdesinin istek thread'inde kalması Spring AI'ın
 * {@code immediateExecution(true)} ayarına dayanır; bu bir iç detay, sözleşme değil. Thread adını
 * her satıra yazmak, ileride bir sürüm yükseltmesi bu davranışı değiştirdiğinde durumu ÜRETİM
 * LOGUNDA görünür kılar — aksi hâlde yalnızca correlation-id sessizce boşalır.
 */
public class ZeusMcpAuditor {

    private static final Logger log = LoggerFactory.getLogger(ZeusMcpAuditor.class);

    private final ZeusMcpProperties properties;

    public ZeusMcpAuditor(ZeusMcpProperties properties) {
        this.properties = properties;
    }

    /** Sonucun ne olduğu — log satırında tek kelimeyle görünür. */
    public enum Sonuc {
        BASARILI, HATA
    }

    /**
     * Bir tool çağrısını kaydeder.
     *
     * @param aracAdi tool adı (MCP'de görünen ad)
     * @param girdi   ham argüman metni; yalnızca {@code zeus.ai.mcp.audit-payload=true} ise loglanır
     * @param ms      geçen süre
     * @param sonuc   başarılı mı
     * @param hata    varsa fırlatılan hata (loglanır, YUTULMAZ — çağıran yeniden fırlatır)
     */
    public void kaydet(String aracAdi, String girdi, long ms, Sonuc sonuc, Throwable hata) {
        String yuk = properties.isAuditPayload() ? " , girdi=" + kirp(girdi) : "";
        if (sonuc == Sonuc.HATA) {
            log.warn("MCP tool çağrıldı — araç={}, süre={} ms, sonuç={}, thread={}{} , hata={}",
                    aracAdi, ms, sonuc, Thread.currentThread().getName(), yuk,
                    hata == null ? "-" : hata.getClass().getSimpleName() + ": " + hata.getMessage());
        } else {
            log.info("MCP tool çağrıldı — araç={}, süre={} ms, sonuç={}, thread={}{}",
                    aracAdi, ms, sonuc, Thread.currentThread().getName(), yuk);
        }
    }

    private String kirp(String s) {
        if (s == null) {
            return "-";
        }
        int sinir = properties.getAuditPayloadMaxLength();
        return s.length() <= sinir ? s : s.substring(0, sinir) + "…(kırpıldı)";
    }
}
