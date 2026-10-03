package com.zeus.framework.ai.mcp;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * Bir {@link ToolCallback}'i audit'leyerek saran dekoratör.
 *
 * <p><b>Neden audit kancası BURADA.</b> Üç aday vardı:
 * <ul>
 *   <li><b>Servlet filtresi</b> — gövde JSON-RPC zarfıdır; hangi tool'un hangi argümanla
 *       çağrıldığını bilmek için gövdeyi tüketip yeniden tamponlamak gerekir. Bu streamable
 *       HTTP'yi bozar ve SDK'nın çerçevelemesini kopyalar. Filtre yalnızca "biri uca POST etti"
 *       diyebilir — erişim denetimi için yeterli, audit için değil.</li>
 *   <li><b>MCP interceptor / sunucu customizer</b> — bizi {@code io.modelcontextprotocol.*}
 *       tiplerine bağlar; yığının en çok değişen katmanı ve modülün {@code afterName}'leri dize
 *       olarak tutma disiplininin var olma sebebi. Üstelik yalnız protokol çağrısını görür,
 *       çözülmüş Java metodunu görmez.</li>
 *   <li><b>Dekoratör (seçilen)</b> — tool kimliği, argüman, süre, sonuç ve çalışan thread'in AYNI
 *       ANDA kapsamda olduğu tek nokta; tamamen {@code org.springframework.ai.tool.*} ile ifade
 *       edilir; uygulamanın ne kaydettiyse onu sarar, app tarafında sıfır kod.</li>
 * </ul>
 *
 * <p><b>Kabul edilen kör nokta:</b> dekoratör yalnızca {@code tools/call}'u görür.
 * {@code initialize} ve {@code tools/list} gibi protokol metotları yalnızca
 * {@link ZeusMcpTokenFilter}'ın satırında kabaca görünür. Protokol seviyesi audit gerekirse SDK
 * bağımlılığının bedeli O ZAMAN ödenir.
 *
 * <p>Hata YUTULMAZ: audit'lenir ve aynen yeniden fırlatılır. Spring AI'ın {@code McpToolUtils}'i
 * fırlatılan hatayı MCP'nin doğru şekline ({@code CallToolResult(isError=true)}) çevirdiği için
 * araya bir ProblemDetail sokmak protokolü bozardı.
 */
class AuditingToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final ZeusMcpAuditor auditor;

    AuditingToolCallback(ToolCallback delegate, ZeusMcpAuditor auditor) {
        this.delegate = delegate;
        this.auditor = auditor;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return auditle(toolInput, () -> delegate.call(toolInput));
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return auditle(toolInput, () -> delegate.call(toolInput, toolContext));
    }

    private String auditle(String girdi, Cagri cagri) {
        String ad = delegate.getToolDefinition().name();
        long baslangic = System.nanoTime();
        try {
            String sonuc = cagri.calistir();
            auditor.kaydet(ad, girdi, gecenMs(baslangic), ZeusMcpAuditor.Sonuc.BASARILI, null);
            return sonuc;
        } catch (RuntimeException e) {
            auditor.kaydet(ad, girdi, gecenMs(baslangic), ZeusMcpAuditor.Sonuc.HATA, e);
            throw e;
        }
    }

    private static long gecenMs(long baslangicNanos) {
        return (System.nanoTime() - baslangicNanos) / 1_000_000;
    }

    @FunctionalInterface
    private interface Cagri {
        String calistir();
    }
}
