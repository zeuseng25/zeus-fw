package com.zeus.framework.ai.agent.workspace;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceToolsTest {

    private ZeusAgentWorkspace ws;
    private WorkspaceTools tools;

    @BeforeEach
    void setUp() {
        ws = new InMemoryWorkspace();
        tools = new WorkspaceTools(ws);
    }

    @Test
    void readFileDurumBasligiylaDoner() {
        ws.write("/r.md", "a\nb\nc\nd\ne");

        String out = tools.readFile("/r.md", 0, 2);

        // Başlık ilk satırda; altındaki her şey BİREBİR dosya içeriği.
        assertThat(out).startsWith("@@ satır 1-2 / 5 | sonraki offset 2 @@\n");
        assertThat(out).endsWith("a\nb");
    }

    @Test
    void sonPenceredeSonrakiOffsetYazilmaz() {
        ws.write("/r.md", "a\nb");
        assertThat(tools.readFile("/r.md", 0, 10)).startsWith("@@ satır 1-2 / 2 @@\n");
    }

    @Test
    void okunmadanDuzenlemeREDDEDILIR() {
        ws.write("/r.md", "merhaba");

        String out = tools.editFile("/r.md", "merhaba", "selam");

        assertThat(out).contains("önce readFile");
        assertThat(ws.read("/r.md", 0, 10).content()).isEqualTo("merhaba"); // değişmedi
    }

    @Test
    void okunduktanSonraDuzenlemeCalisir() {
        ws.write("/r.md", "merhaba");
        tools.readFile("/r.md", 0, 10);

        assertThat(tools.editFile("/r.md", "merhaba", "selam")).contains("güncellendi");
        assertThat(ws.read("/r.md", 0, 10).content()).isEqualTo("selam");
    }

    @Test
    void writeFileOkumaSarti_aramaz() {
        assertThat(tools.writeFile("/yeni.md", "içerik")).contains("yazıldı");
    }

    @Test
    void hataKoduModeleAnlasilirMetinOlarakDoner() {
        assertThat(tools.readFile("/yok.md", 0, 10)).contains("file_not_found");
        assertThat(tools.writeFile("goreli.md", "x")).contains("invalid_path");
    }

    @Test
    void grepDuzMetin_eslesmeYoksaAcikSoyler() {
        ws.write("/a.txt", "fiyat: 100");
        assertThat(tools.grep("fiyat", null)).contains("/a.txt:1:fiyat: 100");
        assertThat(tools.grep("fiyat.*", null)).contains("eşleşme bulunamadı");
    }

    @Test
    void lsBosCalismaAlaniniAcikSoyler() {
        assertThat(tools.ls("/")).contains("boş");
    }
}
