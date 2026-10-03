package com.zeus.framework.ai.agent.workspace;

import com.zeus.framework.ai.agent.workspace.WorkspaceResults.EditResult;
import com.zeus.framework.ai.agent.workspace.WorkspaceResults.GrepResult;
import com.zeus.framework.ai.agent.workspace.WorkspaceResults.LsResult;
import com.zeus.framework.ai.agent.workspace.WorkspaceResults.ReadResult;
import com.zeus.framework.ai.agent.workspace.WorkspaceResults.WorkspaceError;
import com.zeus.framework.ai.agent.workspace.WorkspaceResults.WriteResult;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Koşu kapsamlı, bellek içi çalışma alanı.
 *
 * <p>Bir koşu için bir örnek kurulur ve koşu bitince bırakılır; eşzamanlı erişim YOKTUR
 * (ajan döngüsü tek thread'de ilerler), bu yüzden senkronizasyon da yoktur.
 *
 * <p>Toplam boyut sınırlıdır: uzun koşularda offload/compaction çıktıları birikip heap'i
 * zorlayabilir. Sınır aşılınca yazma {@code too_large} ile reddedilir — model bunu okur ve
 * davranışını değiştirebilir (istisna fırlatmak ajanı öldürürdü).
 */
public class InMemoryWorkspace implements ZeusAgentWorkspace {

    private static final int VARSAYILAN_AZAMI_BAYT = 8 * 1024 * 1024;

    private final Map<String, String> files = new TreeMap<>();
    private final int maxTotalBytes;

    public InMemoryWorkspace() {
        this(VARSAYILAN_AZAMI_BAYT);
    }

    public InMemoryWorkspace(int maxTotalBytes) {
        this.maxTotalBytes = maxTotalBytes;
    }

    /** Koşu sonunda çalışma alanının kopyası (yol → içerik). */
    public Map<String, String> snapshot() {
        return new LinkedHashMap<>(files);
    }

    @Override
    public LsResult ls(String path) {
        String p = normalize(path == null ? "/" : path);
        if (p == null) {
            return LsResult.error(WorkspaceError.INVALID_PATH);
        }
        String prefix = p.endsWith("/") ? p : p + "/";
        List<String> hit = new ArrayList<>();
        for (String key : files.keySet()) {
            if (key.equals(p) || key.startsWith(prefix)) {
                hit.add(key);
            }
        }
        return LsResult.ok(hit);
    }

    @Override
    public ReadResult read(String path, int offset, int limit) {
        String p = normalize(path);
        if (p == null) {
            return ReadResult.error(WorkspaceError.INVALID_PATH);
        }
        String content = files.get(p);
        if (content == null) {
            if (isDirectoryPrefix(p)) {
                return ReadResult.error(WorkspaceError.IS_DIRECTORY);
            }
            return ReadResult.error(WorkspaceError.FILE_NOT_FOUND);
        }
        String[] lines = splitLines(content);
        int total = lines.length;
        int from = Math.max(offset, 0);
        if (from >= total) {
            // Dosya VAR, sadece istenen pencere sınırın dışında — file_not_found DEĞİL.
            return ReadResult.error(WorkspaceError.OFFSET_OUT_OF_RANGE);
        }
        int to = Math.min(from + Math.max(limit, 1), total);
        String body = String.join("\n", List.of(lines).subList(from, to));
        Integer next = (to < total) ? to : null;
        return new ReadResult(body, from + 1, to, total, next, null);
    }

    @Override
    public WriteResult write(String path, String content) {
        String p = normalize(path);
        if (p == null) {
            return WriteResult.error(WorkspaceError.INVALID_PATH);
        }
        if (isDirectoryPrefix(p)) {
            // p, var olan bir dosyanın dizin önekiyse (örn. /alt/a.txt varken /alt), sessizce
            // çakışan bir dosya oluşturmak yerine reddet.
            return WriteResult.error(WorkspaceError.IS_DIRECTORY);
        }
        String yeni = content == null ? "" : content;
        if (toplamBayt(p, yeni) > maxTotalBytes) {
            return WriteResult.error(WorkspaceError.TOO_LARGE);
        }
        files.put(p, yeni);
        return WriteResult.ok(p);
    }

    @Override
    public EditResult edit(String path, String oldText, String newText) {
        String p = normalize(path);
        if (p == null) {
            return EditResult.error(WorkspaceError.INVALID_PATH);
        }
        String content = files.get(p);
        if (content == null) {
            if (isDirectoryPrefix(p)) {
                return EditResult.error(WorkspaceError.IS_DIRECTORY);
            }
            return EditResult.error(WorkspaceError.FILE_NOT_FOUND);
        }
        if (oldText == null || oldText.isEmpty()) {
            return EditResult.error(WorkspaceError.NO_MATCH);
        }
        int ilk = content.indexOf(oldText);
        if (ilk < 0) {
            return EditResult.error(WorkspaceError.NO_MATCH);
        }
        if (content.indexOf(oldText, ilk + oldText.length()) >= 0) {
            // Belirsizliği modele bildir: hangi eşleşmenin kastedildiği bilinemez.
            return EditResult.error(WorkspaceError.MULTIPLE_MATCHES);
        }
        String yeni = content.substring(0, ilk)
                + (newText == null ? "" : newText)
                + content.substring(ilk + oldText.length());
        if (toplamBayt(p, yeni) > maxTotalBytes) {
            return EditResult.error(WorkspaceError.TOO_LARGE);
        }
        files.put(p, yeni);
        return EditResult.ok(p, 1);
    }

    @Override
    public GrepResult grep(String literal, String path) {
        if (literal == null || literal.isEmpty()) {
            return GrepResult.error(WorkspaceError.NO_MATCH);
        }
        String kok = path == null ? "/" : normalize(path);
        if (kok == null) {
            return GrepResult.error(WorkspaceError.INVALID_PATH);
        }
        String prefix = kok.endsWith("/") ? kok : kok + "/";
        List<String> hit = new ArrayList<>();
        for (Map.Entry<String, String> e : files.entrySet()) {
            if (!(e.getKey().equals(kok) || e.getKey().startsWith(prefix))) {
                continue;
            }
            String[] lines = splitLines(e.getValue());
            for (int i = 0; i < lines.length; i++) {
                // DÜZ METİN: contains, regex DEĞİL.
                if (lines[i].contains(literal)) {
                    hit.add(e.getKey() + ":" + (i + 1) + ":" + lines[i]);
                }
            }
        }
        return GrepResult.ok(hit, false);
    }

    /** Mutlak, {@code ..}/{@code ~} içermeyen yol; geçersizse {@code null}. */
    private static String normalize(String path) {
        if (path == null || path.isBlank() || !path.startsWith("/")) {
            return null;
        }
        if (path.contains("..") || path.contains("~")) {
            return null;
        }
        String p = path.replaceAll("/{2,}", "/");
        return (p.length() > 1 && p.endsWith("/")) ? p.substring(0, p.length() - 1) : p;
    }

    /**
     * İçeriği satırlara böler. Sondaki TEK bir {@code \n} bir sonraki (hayalet) boş satırı
     * değil, önceki satırın sonlandırıcısını temsil eder — bu yüzden bölmeden önce atılır.
     * ("a\nb\nc\n" → 3 satır, 4 DEĞİL.)
     */
    private static String[] splitLines(String content) {
        String body = content.endsWith("\n") ? content.substring(0, content.length() - 1) : content;
        return body.split("\n", -1);
    }

    /** {@code p}, saklanan en az bir dosyanın dizin öneki mi? (örn. {@code /alt/a.txt} varken {@code /alt}) */
    private boolean isDirectoryPrefix(String p) {
        String prefix = p.endsWith("/") ? p : p + "/";
        for (String key : files.keySet()) {
            if (key.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private int toplamBayt(String degisenYol, String yeniIcerik) {
        int toplam = yeniIcerik.getBytes(StandardCharsets.UTF_8).length;
        for (Map.Entry<String, String> e : files.entrySet()) {
            if (!e.getKey().equals(degisenYol)) {
                toplam += e.getValue().getBytes(StandardCharsets.UTF_8).length;
            }
        }
        return toplam;
    }
}
