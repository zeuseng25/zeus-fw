package com.zeus.framework.ai.agent.workspace;

import java.util.List;

/**
 * Çalışma alanı işlemlerinin sonuç tipleri.
 *
 * <p><b>Hata istisna değil, DÖNÜŞ DEĞERİDİR.</b> Bu arayüzün tüketicisi modeldir: "dosya yok"
 * bilgisini okuyup kendini düzeltebilmesi gerekir. Fırlatılan bir istisna modeli döngüden
 * düşürür ve ajan hatadan öğrenemez.
 *
 * <p>Hata kodları sabit ve normalize edilmiştir; model serbest metin yerine aynı dizeleri görür.
 */
public final class WorkspaceResults {

    private WorkspaceResults() {
    }

    /** Normalize hata kodları — model her zaman aynı dizeyi görür. */
    public static final class WorkspaceError {
        public static final String FILE_NOT_FOUND = "file_not_found";
        public static final String INVALID_PATH = "invalid_path";
        public static final String IS_DIRECTORY = "is_directory";
        public static final String NO_MATCH = "no_match";
        public static final String MULTIPLE_MATCHES = "multiple_matches";
        public static final String TOO_LARGE = "too_large";

        private WorkspaceError() {
        }
    }

    /** @param paths bulunan yollar (alfabetik) · @param error hata kodu ya da {@code null} */
    public record LsResult(List<String> paths, String error) {
        public static LsResult ok(List<String> paths) {
            return new LsResult(paths, null);
        }

        public static LsResult error(String error) {
            return new LsResult(List.of(), error);
        }
    }

    /**
     * @param content    istenen pencerenin içeriği (başlıksız, ham)
     * @param startLine  1 tabanlı ilk satır
     * @param endLine    1 tabanlı son satır
     * @param totalLines dosyanın toplam satır sayısı
     * @param nextOffset pencere dosyanın sonuna ulaşmadıysa sıradaki offset, ulaştıysa {@code null}
     */
    public record ReadResult(String content, int startLine, int endLine, int totalLines,
                             Integer nextOffset, String error) {
        public static ReadResult error(String error) {
            return new ReadResult(null, 0, 0, 0, null, error);
        }
    }

    public record WriteResult(String path, String error) {
        public static WriteResult ok(String path) {
            return new WriteResult(path, null);
        }

        public static WriteResult error(String error) {
            return new WriteResult(null, error);
        }
    }

    /** @param occurrences değiştirilen eşleşme sayısı (bu sürümde her zaman 1) */
    public record EditResult(String path, int occurrences, String error) {
        public static EditResult ok(String path, int occurrences) {
            return new EditResult(path, occurrences, null);
        }

        public static EditResult error(String error) {
            return new EditResult(null, 0, error);
        }
    }

    /** @param matches {@code yol:satırNo:içerik} biçiminde eşleşmeler */
    public record GrepResult(List<String> matches, boolean truncated, String error) {
        public static GrepResult ok(List<String> matches, boolean truncated) {
            return new GrepResult(matches, truncated, null);
        }

        public static GrepResult error(String error) {
            return new GrepResult(List.of(), false, error);
        }
    }
}
