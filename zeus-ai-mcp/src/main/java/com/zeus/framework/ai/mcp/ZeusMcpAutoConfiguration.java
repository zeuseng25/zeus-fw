package com.zeus.framework.ai.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;

import java.util.List;

/**
 * Zeus AI MCP sunucusu auto-configuration.
 *
 * <p>Uygulamanın {@link ZeusMcpTools} ile AÇIKÇA kaydettiği nesnelerin {@code @Tool} metotlarını
 * tek bir {@link ToolCallbackProvider} olarak sunar; MCP tool şemasına dönüştürmeyi Spring AI'ın
 * {@code ToolCallbackConverterAutoConfiguration}'ı yapar (2026-10-03'te ölçülerek doğrulandı:
 * tool adları metot adlarına, {@code @ToolParam} açıklamaları input şemasına geçiyor). Yani bu
 * modül şema üretmez — kaydı toplar, audit'ler ve ucu korur.
 *
 * <p>Sınıf adları {@code afterName} ile STRING olarak verilir; zeus-ai'deki aynı gerekçeyle:
 * modül Spring AI'ın auto-configure paketlerine derleme zamanı bağımlılığı TAŞIMAZ.
 */
@AutoConfiguration(afterName = {
        "org.springframework.ai.mcp.server.common.autoconfigure.McpServerJsonMapperAutoConfiguration",
        "org.springframework.ai.mcp.server.common.autoconfigure.ToolCallbackConverterAutoConfiguration",
        "org.springframework.ai.mcp.server.webmvc.autoconfigure.McpServerStreamableHttpWebMvcAutoConfiguration"
})
// ToolCallbackProvider spring-ai-model'de yaşar: kararlı, genel API. Koşulu
// io.modelcontextprotocol.* üzerine kurmak, onu yığının en çok değişen katmanına bağlardı.
@ConditionalOnClass(ToolCallbackProvider.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
// matchIfMissing=false: yetenekler OPT-IN'dir (bkz. com.zeus.framework.autoconfig.ZeusCapabilities).
// SİMETRİ KURALI: bu anahtar, veto ettiği 3. parti yığınla AYNI anahtar olmalıdır; aksi hâlde
// zeus.ai.mcp.enabled=false yazan uygulamada bu config yüklenip altındaki bean'ler veto edilir.
@ConditionalOnProperty(prefix = "zeus.ai.mcp", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(ZeusMcpProperties.class)
public class ZeusMcpAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ZeusMcpAutoConfiguration.class);

    static final String PROTOCOL_PROPERTY = "spring.ai.mcp.server.protocol";
    static final String ENDPOINT_PROPERTY = "spring.ai.mcp.server.streamable-http.mcp-endpoint";
    static final String DESTEKLENEN_PROTOCOL = "STREAMABLE";

    private final String ucYolu;

    public ZeusMcpAutoConfiguration(ZeusMcpProperties properties, Environment environment) {
        dogrulaToken(properties);
        this.ucYolu = dogrulaTasima(environment);
        log.info("Zeus AI MCP modülü yüklendi — uç: {} , paylaşılan sır: '{}' header'ından.",
                ucYolu, properties.getTokenHeader());
    }

    /**
     * FAIL-CLOSED. Token yoksa açılış reddedilir.
     *
     * <p><b>Neden koşul değil, doğrulama.</b> Token varlığına {@code @Conditional} koymak YANLIŞ
     * olurdu: o durumda Spring AI'ın router function'ı yayında kalırken bizim filtremiz ve
     * tool'larımız devre dışı kalır — mümkün olan en kötü sonuç (korumasız, tool'suz bir uç).
     * Yeteneğin kapalı olması ise autoconfig yığınının TAMAMINI veto eder, yani uç hiç oluşmaz.
     * Erişilebilir iki durum bunlardır; arada "uç var, koruma yok" hâli bırakılmamıştır.
     */
    private static void dogrulaToken(ZeusMcpProperties properties) {
        String token = properties.getToken();
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("""
                    Zeus MCP yapılandırma hatası: 'zeus.ai.mcp.enabled=true' yazılmış ama \
                    'zeus.ai.mcp.token' boş.
                          MCP ucu ağa açılır; paylaşılan sır olmadan yayına alınmaz (fail-closed).
                          application.properties'e ekleyin:  zeus.ai.mcp.token=${ZEUS_MCP_TOKEN:}
                          ve ZEUS_MCP_TOKEN ortam değişkenini tanımlayın (sır REPOYA YAZILMAZ).
                          Yeteneği kullanmayacaksanız:  zeus.ai.mcp.enabled=false""");
        }
    }

    /**
     * Taşıma seçimini doğrular ve uç yolunu döner.
     *
     * <p><b>Bu bir GÜVENLİK kontrolüdür, hijyen değil</b> (2026-10-03 ölçümü): {@code protocol}
     * yazılmazsa Spring AI, deprecated SSE taşımasını yayınlar — ölçülen davranış, çünkü
     * streamable autoconfig {@code STREAMABLE}'ı açıkça isterken SSE {@code matchIfMissing=true}
     * taşır. SSE'nin ucu AYRI bir property uzayından gelir
     * ({@code spring.ai.mcp.server.sse-endpoint}), dolayısıyla yolunu streamable property'sinden
     * türeten {@link ZeusMcpTokenFilter} onu KORUMAZ. Bu yüzden kural tek cümledir:
     * zeus-ai-mcp yalnız {@code STREAMABLE} destekler, başka her değer açılışı reddeder.
     */
    private static String dogrulaTasima(Environment environment) {
        String protocol = environment.getProperty(PROTOCOL_PROPERTY);
        if (!DESTEKLENEN_PROTOCOL.equalsIgnoreCase(protocol)) {
            throw new IllegalStateException("""
                    Zeus MCP yapılandırma hatası: '%s' değeri '%s' (beklenen: %s).
                          Bu property YAZILMAZSA Spring AI, kullanım dışı bırakılmış SSE taşımasını \
                    yayınlar; SSE'nin ucu ayrı bir property'den (spring.ai.mcp.server.sse-endpoint) \
                    geldiği için zeus token filtresi onu KORUMAZ.
                          application.properties'e ekleyin:  %s=%s"""
                    .formatted(PROTOCOL_PROPERTY, protocol == null ? "yazılmamış" : protocol,
                            DESTEKLENEN_PROTOCOL, PROTOCOL_PROPERTY, DESTEKLENEN_PROTOCOL));
        }
        String uc = environment.getProperty(ENDPOINT_PROPERTY);
        if (uc == null || uc.isBlank()) {
            throw new IllegalStateException("""
                    Zeus MCP yapılandırma hatası: '%s' yazılmamış.
                          Varsayılan '/mcp'dir; uç yolu, korunacak yolun TEK kaynağı olduğu için \
                    açıkça bildirilmelidir.
                          application.properties'e ekleyin:  %s=/api/mcp"""
                    .formatted(ENDPOINT_PROPERTY, ENDPOINT_PROPERTY));
        }
        return uc;
    }

    /**
     * Uygulamanın kaydettiği tool'lar, audit dekoratörüyle sarılmış hâlde.
     *
     * <p>{@code @ConditionalOnBean(ZeusMcpTools.class)}: yetenek açık ama kayıt bean'i yoksa bu
     * bean oluşmaz ve MCP sunucusu SIFIR tool ile açılır — güvenli ve gözlenebilir bir durum
     * (ölçüldü: context ayakta kalıyor). "Unutulan kayıt" sessiz bir yayına dönüşmez.
     */
    @Bean
    @ConditionalOnBean(ZeusMcpTools.class)
    @ConditionalOnMissingBean(name = "zeusMcpToolCallbackProvider")
    public ToolCallbackProvider zeusMcpToolCallbackProvider(ZeusMcpTools tools, ZeusMcpAuditor auditor) {
        List<ToolCallback> sarilmis = tools.toolCallbacks().stream()
                .map(cb -> (ToolCallback) new AuditingToolCallback(cb, auditor))
                .toList();
        log.info("Zeus AI MCP: {} tool yayınlanıyor — {}", sarilmis.size(),
                sarilmis.stream().map(cb -> cb.getToolDefinition().name()).toList());
        return ToolCallbackProvider.from(sarilmis);
    }

    @Bean
    @ConditionalOnMissingBean
    public ZeusMcpAuditor zeusMcpAuditor(ZeusMcpProperties properties) {
        return new ZeusMcpAuditor(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @Order(Ordered.HIGHEST_PRECEDENCE + 20)
    public ZeusMcpTokenFilter zeusMcpTokenFilter(ZeusMcpProperties properties) {
        return new ZeusMcpTokenFilter(ucYolu, properties);
    }

    /**
     * Kayıt bean'i olmadığında bir kez uyarır. Yetenek açıkken tool yayınlamamak meşrudur, ama
     * sessiz kalmak "unuttum" ile "bilinçli" arasındaki farkı gizler.
     */
    @Bean
    @ConditionalOnMissingBean(ZeusMcpTools.class)
    public ZeusMcpKayitUyarisi zeusMcpKayitUyarisi() {
        return new ZeusMcpKayitUyarisi();
    }

    /** Yalnızca uyarı basmak için var olan işaret bean'i. */
    static class ZeusMcpKayitUyarisi {
        ZeusMcpKayitUyarisi() {
            log.warn("Zeus AI MCP açık ama ZeusMcpTools bean'i YOK — sunucu sıfır tool ile açılıyor. "
                    + "Yayınlamak için bir @Bean ZeusMcpTools tanımlayın: ZeusMcpTools.of(araclarim). "
                    + "Bilinçli olarak boşsa ZeusMcpTools.none() ile bildirin.");
        }
    }
}
