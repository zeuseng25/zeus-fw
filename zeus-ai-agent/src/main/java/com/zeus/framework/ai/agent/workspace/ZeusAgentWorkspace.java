package com.zeus.framework.ai.agent.workspace;

import com.zeus.framework.ai.agent.workspace.WorkspaceResults.EditResult;
import com.zeus.framework.ai.agent.workspace.WorkspaceResults.GrepResult;
import com.zeus.framework.ai.agent.workspace.WorkspaceResults.LsResult;
import com.zeus.framework.ai.agent.workspace.WorkspaceResults.ReadResult;
import com.zeus.framework.ai.agent.workspace.WorkspaceResults.WriteResult;

/**
 * Ajanın çalışma alanı — koşu boyunca ara çıktıların ve nihai raporun yazıldığı sanal dosya
 * sistemi.
 *
 * <p><b>Neden var:</b> araştırma ajanının asıl çıktısı sohbetin son cümlesi değil, ÜRETTİĞİ
 * DOSYALARDIR. Ara bulguları bağlamda biriktirmek yerine dosyaya yazmak, uzun koşuların bağlamı
 * patlatmasını da önler.
 *
 * <p>Yollar POSIX benzeridir ve MUTLAK olmalıdır ({@code /} ile başlar). {@code ..} ve {@code ~}
 * reddedilir.
 *
 * <p>Hiçbir metot istisna fırlatmaz; hatalar sonuç tipindeki {@code error} alanında döner
 * (bkz. {@link WorkspaceResults}).
 */
public interface ZeusAgentWorkspace {

    /** Verilen dizinin altındaki dosya yolları (özyinelemeli, alfabetik). */
    LsResult ls(String path);

    /**
     * Dosyanın bir penceresini okur.
     *
     * @param offset 0 tabanlı başlangıç satırı; negatifse 0 kabul edilir
     * @param limit  en çok kaç satır okunacağı
     */
    ReadResult read(String path, int offset, int limit);

    /** Dosyayı yazar; yoksa oluşturur, varsa TAMAMEN değiştirir. */
    WriteResult write(String path, String content);

    /** {@code oldText}'in TEK eşleşmesini {@code newText} ile değiştirir. */
    EditResult edit(String path, String oldText, String newText);

    /**
     * DÜZ METİN arama (regex değil).
     *
     * @param path arama kökü; {@code null} ise tüm çalışma alanı
     */
    GrepResult grep(String literal, String path);
}
