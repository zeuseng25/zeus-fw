package com.zeus.framework.ai.agent.workspace;

import com.zeus.framework.ai.agent.workspace.WorkspaceResults.EditResult;
import com.zeus.framework.ai.agent.workspace.WorkspaceResults.GrepResult;
import com.zeus.framework.ai.agent.workspace.WorkspaceResults.LsResult;
import com.zeus.framework.ai.agent.workspace.WorkspaceResults.ReadResult;
import com.zeus.framework.ai.agent.workspace.WorkspaceResults.WriteResult;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.HashSet;
import java.util.Set;

/**
 * Çalışma alanını modele açan tool'lar — koşu başına bir örnek.
 *
 * <p><b>Bu sınıfın açıklamaları bu modülün asıl PROMPTUDUR.</b> deepagents'ın ölçülmüş bulgusu:
 * varsayılan sistem promptu boştur; davranış tool açıklamalarında yaşar. Açıklamalar
 * {@code ToolDescriptionSnapshotTest} ile kilitlidir.
 *
 * <p>"Okumadan düzenleme yok" kuralı BU SINIFTA tutulur (çalışma alanında değil): kural modelin
 * davranışıyla ilgilidir, depolamayla değil. Okunan dosyalar koşu boyunca hatırlanır.
 */
public class WorkspaceTools {

    private final ZeusAgentWorkspace workspace;
    private final Set<String> okunanlar = new HashSet<>();

    public WorkspaceTools(ZeusAgentWorkspace workspace) {
        this.workspace = workspace;
    }

    @Tool(description = """
            Çalışma alanındaki dosyaları listeler (özyinelemeli).
            Doğru dosyayı bulmak için readFile ya da editFile'dan ÖNCE neredeyse her zaman \
            bunu kullan.""")
    public String ls(@ToolParam(description = "Mutlak dizin yolu, ör. /") String path) {
        LsResult r = workspace.ls(path);
        if (r.error() != null) {
            return "HATA: " + r.error();
        }
        if (r.paths().isEmpty()) {
            return "Çalışma alanı boş (bu yolun altında dosya yok).";
        }
        return String.join("\n", r.paths());
    }

    @Tool(description = """
            Çalışma alanından bir dosyayı okur.

            Kullanım:
            - Varsayılan olarak baştan en çok 100 satır okur. Büyük dosyaları bütün hâlinde \
            okumak yerine offset/limit ile sayfalayarak oku.
            - İçeriğin üstünde `@@ satır A-B / T | sonraki offset N @@` biçiminde bir durum \
            başlığı bulunur; başlığın ALTINDAKİ her satır birebir dosya içeriğidir. \
            Düzenleme yaparken bu başlığı ASLA dâhil etme.
            - Dosya yoksa hata döner; yolu uydurma.
            - Düzenlemeden önce dosyayı MUTLAKA oku.""")
    public String readFile(
            @ToolParam(description = "Mutlak dosya yolu") String filePath,
            @ToolParam(description = "0 tabanlı başlangıç satırı; verilmezse 0", required = false) Integer offset,
            @ToolParam(description = "En çok kaç satır; verilmezse 100", required = false) Integer limit) {

        int off = offset == null ? 0 : offset;
        int lim = limit == null ? 100 : limit;
        ReadResult r = workspace.read(filePath, off, lim);
        if (r.error() != null) {
            return "HATA: " + r.error();
        }
        okunanlar.add(filePath);

        String baslik = "@@ satır " + r.startLine() + "-" + r.endLine() + " / " + r.totalLines()
                + (r.nextOffset() != null ? " | sonraki offset " + r.nextOffset() : "") + " @@";
        return baslik + "\n" + r.content();
    }

    @Tool(description = """
            Dosyaya içerik yazar. Dosya yoksa oluşturur, varsa İÇERİĞİNİ TAMAMEN DEĞİŞTİRİR.

            Kullanım:
            - Yeni dosya oluşturmak ya da dosyayı baştan yazmak istediğinde kullan; \
            önce okumana gerek yoktur.
            - Mevcut bir dosyanın bir kısmını değiştireceksen writeFile yerine editFile tercih et.
            - Az önce writeFile ile yazdığın bir dosyayı ayrıca okumana gerek yok; doğrudan \
            editFile ile düzenleyebilirsin.
            - Araştırma bulgularını ve nihai raporu buraya yaz; sohbete uzun metin dökme.""")
    public String writeFile(
            @ToolParam(description = "Mutlak dosya yolu") String filePath,
            @ToolParam(description = "Dosyanın tam içeriği") String content) {

        WriteResult r = workspace.write(filePath, content);
        if (r.error() != null) {
            return "HATA: " + r.error();
        }
        okunanlar.add(filePath);   // yazan taraf içeriği bilir
        return r.path() + " yazıldı.";
    }

    @Tool(description = """
            Dosyada birebir metin değişimi yapar.

            Kullanım:
            - Önce readFile ile okumalısın; okumadan düzenleme REDDEDİLİR.
            - old_string dosyada tek bir yerde geçmelidir; birden çok eşleşme varsa hata döner \
            (hangisinin kastedildiği bilinemez) — daha uzun ve benzersiz bir parça ver.
            - Okuma çıktısındaki girintiyi birebir koru ve durum başlığını (@@ ... @@) \
            old_string veya new_string içine ASLA koyma.""")
    public String editFile(
            @ToolParam(description = "Mutlak dosya yolu") String filePath,
            @ToolParam(description = "Değiştirilecek birebir metin") String oldString,
            @ToolParam(description = "Yerine yazılacak metin") String newString) {

        if (!okunanlar.contains(filePath)) {
            return "HATA: bu dosya bu koşuda okunmadı — düzenlemeden önce readFile ile oku.";
        }
        EditResult r = workspace.edit(filePath, oldString, newString);
        if (r.error() != null) {
            return "HATA: " + r.error();
        }
        return r.path() + " güncellendi (" + r.occurrences() + " değişiklik).";
    }

    @Tool(description = """
            Çalışma alanında DÜZ METİN arar (regex DEĞİL).

            Desen birebir aranır: regex meta karakterleri operatör değil, sıradan karakterdir. \
            Örneğin "fiyat.*" araması birebir "fiyat.*" metnini arar; "." herhangi bir karakter \
            anlamına gelmez. Birkaç farklı metni aramak için ayrı ayrı grep çağır.

            Sonuç `yol:satır:içerik` biçiminde döner.""")
    public String grep(
            @ToolParam(description = "Aranacak birebir metin") String pattern,
            @ToolParam(description = "Arama kökü; verilmezse tüm çalışma alanı", required = false) String path) {

        GrepResult r = workspace.grep(pattern, path);
        if (r.error() != null) {
            return "HATA: " + r.error();
        }
        if (r.matches().isEmpty()) {
            return "eşleşme bulunamadı";
        }
        return String.join("\n", r.matches());
    }
}
