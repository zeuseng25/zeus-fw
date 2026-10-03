package com.zeus.framework.ai.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Zeus AI MCP sunucusu ayarları ({@code zeus.ai.mcp.*}).
 *
 * <p><b>Taşıma ayarları burada DEĞİL.</b> Uç yolu, protokol ve timeout'lar Spring AI'ın kendi
 * property'lerinde kalır ({@code spring.ai.mcp.server.*}) — zeus-ai'nin
 * {@code spring.ai.openai.*}'ı ikizlememe kararıyla aynı çizgi. Burada özel bir sebep daha var:
 * token filtresi korunacak yolu, taşımanın yayınladığı yolu okuduğu property'den türetir. Aynı
 * değeri iki property'de tutmak, "korunan yol" ile "yayınlanan yol"un ayrışmasına kapı açardı —
 * bu modülün yapabileceği en tehlikeli hata.
 */
@ConfigurationProperties(prefix = "zeus.ai.mcp")
public class ZeusMcpProperties {

    /**
     * Yeteneğin açık olup olmadığı.
     *
     * <p><b>Bu alan hiçbir yerde OKUNMAZ</b> ve burada yalnızca IDE/metadata tamamlaması için
     * durur. Gerçek kapı {@code ZeusMcpAutoConfiguration} üzerindeki
     * {@code @ConditionalOnProperty} ve {@code ZeusCapabilities}'teki {@code ai-mcp} kaydıdır.
     * Bu yüzden Java varsayılanı bilinçli olarak {@code false}: zeus-ai'de aynı alanın
     * {@code true} varsayılanı, dokümanın "VARSAYILAN KAPALI" ifadesiyle çelişen ölü bir koddur.
     *
     * <p><b>ÖNEMLİ — jar'ı olmayan uygulamada bu property'yi YAZMAYIN.</b> {@code zeus-ai-mcp}
     * bağımlılığı pom'da yokken {@code zeus.ai.mcp.enabled=true} yazmak, hiçbir çalışma zamanı
     * denetiminin yakalayamayacağı bir duruma yol açar: işaretçi sınıf yüklenemediği için
     * {@code ZeusCapabilityVerifier} sessiz kalır, ama paylaşımlı module'deki Spring AI MCP
     * autoconfig'leri veto edilmediği için SUNUCU AYAĞA KALKAR — bizim filtremiz ve audit'imiz
     * olmadan. Build tarafındaki {@code scripts/test-mcp-opt-in-butunlugu.sh} guard'ı tam bunu
     * denetler.
     */
    private boolean enabled = false;

    /** Paylaşılan sırrın taşındığı HTTP header adı. */
    private String tokenHeader = "X-Zeus-Mcp-Token";

    /**
     * Paylaşılan sır. Değer ortam değişkeninden gelir ({@code ${ZEUS_MCP_TOKEN:}}) ve ASLA
     * repoya yazılmaz. Boş bırakılırsa yetenek açıkken açılış REDDEDİLİR (fail-closed).
     *
     * <p>Bu bir kimlik değil, taşıyıcı yetkidir: tüm çağıranlar aynı principal'dır, tool bazlı
     * yetki yoktur ve iptal = sır rotasyonu + restart. Gerçek kimlik gelecek zeus-security
     * modülünün işi (bkz. gelistirmeler/23-zeus-ai-mcp.md, "Bilinen boşluk").
     */
    private String token;

    /**
     * Audit satırına tool argümanlarını da yazar. Varsayılan KAPALI: argümanlar kişisel veri
     * taşıyabilir ve log bir kasa değildir.
     */
    private boolean auditPayload = false;

    /** {@link #auditPayload} açıkken argüman metninin kırpılacağı uzunluk. */
    private int auditPayloadMaxLength = 512;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getTokenHeader() {
        return tokenHeader;
    }

    public void setTokenHeader(String tokenHeader) {
        this.tokenHeader = tokenHeader;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public boolean isAuditPayload() {
        return auditPayload;
    }

    public void setAuditPayload(boolean auditPayload) {
        this.auditPayload = auditPayload;
    }

    public int getAuditPayloadMaxLength() {
        return auditPayloadMaxLength;
    }

    public void setAuditPayloadMaxLength(int auditPayloadMaxLength) {
        this.auditPayloadMaxLength = auditPayloadMaxLength;
    }
}
