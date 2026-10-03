package com.zeus.framework.ai.agent.workspace;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tool AÇIKLAMALARININ golden snapshot'ı.
 *
 * <p>Bu modülde davranış sistem promptunda değil, tool açıklamalarında yaşıyor (deepagents'ın
 * ölçülmüş bulgusu: varsayılan sistem promptu boş). Bir açıklamayı sessizce düzenlemek ajanın
 * davranışını değiştirir; bu test o değişikliği review'da GÖRÜNÜR kılar.
 *
 * <p>Açıklama bilinçli olarak değiştiyse beklenen metni burada güncelleyin — bu bir kod
 * değişikliği kadar ciddiye alınmalıdır.
 */
class ToolDescriptionSnapshotTest {

    private Map<String, String> aciklamalar() {
        ToolCallback[] cbs = MethodToolCallbackProvider.builder()
                .toolObjects(new WorkspaceTools(new InMemoryWorkspace()))
                .build().getToolCallbacks();
        return Arrays.stream(cbs).collect(Collectors.toMap(
                cb -> cb.getToolDefinition().name(), cb -> cb.getToolDefinition().description()));
    }

    @Test
    void besToolYayinlanir() {
        assertThat(aciklamalar().keySet())
                .containsExactlyInAnyOrder("ls", "readFile", "writeFile", "editFile", "grep");
    }

    @Test
    void readFileAciklamasiSayfalamaVeBaslikKuraliniAnlatir() {
        String d = aciklamalar().get("readFile");
        assertThat(d).contains("@@");
        assertThat(d).contains("Düzenlemeden önce dosyayı MUTLAKA oku");
        assertThat(d).contains("offset");
    }

    @Test
    void editFileAciklamasiOkumaSartiniVeBaslikYasaginiAnlatir() {
        String d = aciklamalar().get("editFile");
        assertThat(d).contains("Önce readFile ile okumalısın");
        assertThat(d).contains("durum başlığını");
        assertThat(d).contains("tek bir yerde");
    }

    @Test
    void writeFileAciklamasiOkumadanDuzenlenebileceginiAnlatir() {
        String d = aciklamalar().get("writeFile");
        assertThat(d).contains("Az önce writeFile ile yazdığın bir dosyayı ayrıca okumana gerek yok");
    }

    @Test
    void grepAciklamasiDUZMETINoldugunuVurgular() {
        String d = aciklamalar().get("grep");
        assertThat(d).contains("DÜZ METİN");
        assertThat(d).contains("regex DEĞİL");
    }
}
