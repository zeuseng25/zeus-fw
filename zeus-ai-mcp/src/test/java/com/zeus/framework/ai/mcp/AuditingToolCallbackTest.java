package com.zeus.framework.ai.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Audit dekoratörünün sözleşmesi: şeffaf ol, hiçbir şeyi yutma.
 */
class AuditingToolCallbackTest {

    private static final ToolDefinition TANIM = DefaultToolDefinition.builder()
            .name("deneme").description("deneme tool").inputSchema("{}").build();

    private final ZeusMcpAuditor auditor = new ZeusMcpAuditor(new ZeusMcpProperties());

    /** Sayaçlı sahte delegate. */
    private static class SahteCallback implements ToolCallback {
        final AtomicInteger cagriSayisi = new AtomicInteger();
        private final RuntimeException firlatilacak;

        SahteCallback(RuntimeException firlatilacak) {
            this.firlatilacak = firlatilacak;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return TANIM;
        }

        @Override
        public String call(String toolInput) {
            cagriSayisi.incrementAndGet();
            if (firlatilacak != null) {
                throw firlatilacak;
            }
            return "sonuç:" + toolInput;
        }
    }

    @Test
    void toolTanimiAynenGecer() {
        ToolCallback sarilmis = new AuditingToolCallback(new SahteCallback(null), auditor);
        assertThat(sarilmis.getToolDefinition()).isSameAs(TANIM);
        assertThat(sarilmis.getToolMetadata()).isNotNull();
    }

    @Test
    void basariliCagriSonucuDegistirmez() {
        SahteCallback delegate = new SahteCallback(null);
        ToolCallback sarilmis = new AuditingToolCallback(delegate, auditor);
        assertThat(sarilmis.call("{\"id\":1}")).isEqualTo("sonuç:{\"id\":1}");
        assertThat(delegate.cagriSayisi).hasValue(1);
    }

    @Test
    void toolContextliCagriDaDelegeEdilir() {
        SahteCallback delegate = new SahteCallback(null);
        ToolCallback sarilmis = new AuditingToolCallback(delegate, auditor);
        assertThat(sarilmis.call("{}", new ToolContext(Map.of("k", "v")))).isEqualTo("sonuç:{}");
        assertThat(delegate.cagriSayisi).hasValue(1);
    }

    @Test
    void hataAUDITLENIR_ve_AYNEN_YENIDEN_FIRLATILIR() {
        // YUTMAMA KANITI. Spring AI'ın McpToolUtils'i fırlatılan hatayı MCP'nin doğru şekline
        // (CallToolResult isError=true) çevirir; dekoratör araya girip bunu bozmamalı.
        IllegalStateException beklenen = new IllegalStateException("tool patladı");
        SahteCallback delegate = new SahteCallback(beklenen);
        ToolCallback sarilmis = new AuditingToolCallback(delegate, auditor);

        assertThatThrownBy(() -> sarilmis.call("{}")).isSameAs(beklenen);
        assertThat(delegate.cagriSayisi).hasValue(1);
    }
}
