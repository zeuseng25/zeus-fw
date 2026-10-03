package com.zeus.framework.ai.agent.workspace;

import com.zeus.framework.ai.agent.workspace.WorkspaceResults.WorkspaceError;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryWorkspaceTest {

    private final ZeusAgentWorkspace ws = new InMemoryWorkspace();

    @Test
    void yazilanDosyaOkunur() {
        assertThat(ws.write("/rapor.md", "bir\niki\nüç").error()).isNull();

        var r = ws.read("/rapor.md", 0, 100);
        assertThat(r.error()).isNull();
        assertThat(r.content()).isEqualTo("bir\niki\nüç");
        assertThat(r.startLine()).isEqualTo(1);
        assertThat(r.endLine()).isEqualTo(3);
        assertThat(r.totalLines()).isEqualTo(3);
        assertThat(r.nextOffset()).isNull();   // sona ulaşıldı
    }

    @Test
    void sayfalamaSonrakiOffsetiVerir() {
        ws.write("/uzun.txt", "a\nb\nc\nd\ne");

        var ilk = ws.read("/uzun.txt", 0, 2);
        assertThat(ilk.content()).isEqualTo("a\nb");
        assertThat(ilk.startLine()).isEqualTo(1);
        assertThat(ilk.endLine()).isEqualTo(2);
        assertThat(ilk.totalLines()).isEqualTo(5);
        assertThat(ilk.nextOffset()).isEqualTo(2);

        var son = ws.read("/uzun.txt", 4, 2);
        assertThat(son.content()).isEqualTo("e");
        assertThat(son.nextOffset()).isNull();
    }

    @Test
    void negatifOffsetBastanOkur() {
        ws.write("/x.txt", "a\nb");
        var r = ws.read("/x.txt", -5, 10);
        assertThat(r.error()).isNull();
        assertThat(r.startLine()).isEqualTo(1);
    }

    @Test
    void olmayanDosyaFileNotFound() {
        assertThat(ws.read("/yok.txt", 0, 10).error()).isEqualTo(WorkspaceError.FILE_NOT_FOUND);
    }

    @Test
    void gecersizYollarReddedilir() {
        assertThat(ws.write("goreli.txt", "x").error()).isEqualTo(WorkspaceError.INVALID_PATH);
        assertThat(ws.write("/a/../b.txt", "x").error()).isEqualTo(WorkspaceError.INVALID_PATH);
        assertThat(ws.write("/~/gizli", "x").error()).isEqualTo(WorkspaceError.INVALID_PATH);
    }

    @Test
    void editTekEslesmeyiDegistirir() {
        ws.write("/n.md", "merhaba dünya");
        var e = ws.edit("/n.md", "dünya", "zeus");
        assertThat(e.error()).isNull();
        assertThat(e.occurrences()).isEqualTo(1);
        assertThat(ws.read("/n.md", 0, 10).content()).isEqualTo("merhaba zeus");
    }

    @Test
    void editCoklamaVeEslesmemeDurumlariniAyirir() {
        ws.write("/c.md", "aa aa");
        assertThat(ws.edit("/c.md", "aa", "bb").error()).isEqualTo(WorkspaceError.MULTIPLE_MATCHES);
        assertThat(ws.edit("/c.md", "zz", "bb").error()).isEqualTo(WorkspaceError.NO_MATCH);
    }

    @Test
    void lsOzyinelemeliVeAlfabetik() {
        ws.write("/b.txt", "1");
        ws.write("/alt/a.txt", "1");
        assertThat(ws.ls("/").paths()).containsExactly("/alt/a.txt", "/b.txt");
        assertThat(ws.ls("/alt").paths()).containsExactly("/alt/a.txt");
    }

    @Test
    void grepDuzMetinArar_regexDegil() {
        ws.write("/g.txt", "fiyat: 25000\nstok: 10");
        ws.write("/h.txt", "fiyat.*");

        assertThat(ws.grep("fiyat", null).matches())
                .containsExactly("/g.txt:1:fiyat: 25000", "/h.txt:1:fiyat.*");
        // '.' bir joker DEĞİL, birebir karakter:
        assertThat(ws.grep("fiyat.*", null).matches()).containsExactly("/h.txt:1:fiyat.*");
    }

    @Test
    void boyutSiniriAsilinciTooLarge() {
        ZeusAgentWorkspace kucuk = new InMemoryWorkspace(10);
        assertThat(kucuk.write("/a", "0123456789_fazla").error()).isEqualTo(WorkspaceError.TOO_LARGE);
    }

    @Test
    void snapshotYazilanlariVerir() {
        ws.write("/a.md", "içerik");
        assertThat(((InMemoryWorkspace) ws).snapshot()).containsEntry("/a.md", "içerik");
    }

    @Test
    void sondakiTekYeniSatirHayaletSatirOlusturmaz() {
        ws.write("/t.txt", "a\nb\nc\n");

        var r = ws.read("/t.txt", 0, 100);
        assertThat(r.error()).isNull();
        assertThat(r.totalLines()).isEqualTo(3);   // 4 DEĞİL — sondaki \n hayalet satır değildir.
        assertThat(r.content()).isEqualTo("a\nb\nc");
        assertThat(r.endLine()).isEqualTo(3);
        assertThat(r.nextOffset()).isNull();
    }

    @Test
    void varOlanDosyadaSinirDisiOffsetFileNotFoundDegildir() {
        ws.write("/v.txt", "a\nb");

        var r = ws.read("/v.txt", 5, 10);
        assertThat(r.error()).isEqualTo(WorkspaceError.OFFSET_OUT_OF_RANGE);
        assertThat(r.error()).isNotEqualTo(WorkspaceError.FILE_NOT_FOUND);
    }

    @Test
    void dizinOnekiOlanYolIsDirectoryDondurur() {
        ws.write("/alt/a.txt", "1");

        // /alt kendisi bir dosya değil, /alt/a.txt'nin dizin öneki — file_not_found DEĞİL.
        assertThat(ws.read("/alt", 0, 10).error()).isEqualTo(WorkspaceError.IS_DIRECTORY);
        assertThat(ws.edit("/alt", "x", "y").error()).isEqualTo(WorkspaceError.IS_DIRECTORY);
        assertThat(ws.write("/alt", "çakışan").error()).isEqualTo(WorkspaceError.IS_DIRECTORY);
    }
}
