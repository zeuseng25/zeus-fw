package com.zeus.framework.autoconfig;

import java.util.List;

/**
 * Bir zeus yeteneğinin sözleşmesi.
 *
 * @param ad                kısa ad (log/hata mesajlarında görünür), ör. "ai"
 * @param property          uygulamanın yazacağı anahtar, ör. "zeus.ai.enabled"
 * @param isaretciSinif     yetenek modülünün kendi *AutoConfiguration sınıfının TAM ADI.
 *                          DİZE olarak tutulur: zeus-base bu modüllere derleme zamanında
 *                          bağlanmaz (bağlansa her şeyi çeker, paylaşımlı module çöker).
 * @param autoconfigOnekleri bu yeteneğin SAHİPLENDİĞİ 3. parti autoconfig paket önekleri
 */
public record ZeusCapability(String ad, String property, String isaretciSinif,
                             List<String> autoconfigOnekleri) {

    /** Verilen autoconfig sınıfı bu yeteneğe mi ait? */
    public boolean sahiplenir(String autoconfigSinifAdi) {
        return autoconfigOnekleri.stream().anyMatch(autoconfigSinifAdi::startsWith);
    }
}
