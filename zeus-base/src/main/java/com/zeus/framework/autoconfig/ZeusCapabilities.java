package com.zeus.framework.autoconfig;

import java.util.List;
import java.util.Optional;

/**
 * Yetenek kaydı — paylaşımlı com.zeus module'ündeki 3. parti autoconfig'lerin sahiplik tablosu.
 *
 * NEDEN VAR: com.zeus tüm uygulamaların bağımlılık BİRLEŞİMİDİR. AI geliştiren uygulama da
 * geliştirmeyen de aynı module'ü paylaşır; module küçültülmez (bu, paylaşımın kendisini
 * çökertirdi). Bu yüzden daraltma Spring seviyesinde yapılır: bir yeteneğin autoconfig'leri
 * yalnız uygulama o yeteneği AÇIKÇA istediğinde çalışır.
 *
 * BURAYA YENİ YETENEK EKLERKEN: scripts/test-autoconfig-sahipligi.sh, module'deki her
 * autoconfig sınıfının ya bir yeteneğe ya da HER_ZAMAN_SERBEST'e düştüğünü denetler.
 * Sınıflandırılmamış bir autoconfig build'i KIRAR — sessizce her uygulamada çalışmaya başlamaz.
 */
public final class ZeusCapabilities {

    private ZeusCapabilities() {
    }

    public static final List<ZeusCapability> HEPSI = List.of(
            new ZeusCapability("ai", "zeus.ai.enabled",
                    "com.zeus.framework.ai.ZeusAiAutoConfiguration",
                    List.of("org.springframework.ai.")),

            new ZeusCapability("database", "zeus.database.enabled",
                    "com.zeus.framework.database.ZeusDatabaseAutoConfiguration",
                    List.of("org.springframework.boot.jdbc.autoconfigure.",
                            "org.springframework.boot.hibernate.autoconfigure.",
                            "org.springframework.boot.data.jpa.autoconfigure.",
                            "org.springframework.boot.persistence.autoconfigure.")),

            new ZeusCapability("soap", "zeus.soap.enabled",
                    "com.zeus.framework.soap.ZeusSoapAutoConfiguration",
                    List.of("org.apache.cxf.spring.boot.autoconfigure.")));

    /**
     * Hiçbir yeteneğe ait olmayan, HER uygulamada çalışması gereken yığın.
     * springdoc bilinçli olarak buradadır: Swagger her uygulamada varsayılan açıktır (karar 5).
     */
    public static final List<String> HER_ZAMAN_SERBEST = List.of(
            "org.springframework.boot.autoconfigure.",
            "org.springframework.boot.webmvc.autoconfigure.",
            "org.springframework.boot.servlet.autoconfigure.",
            "org.springframework.boot.jackson.autoconfigure.",
            "org.springframework.boot.validation.autoconfigure.",
            "org.springframework.boot.http.",
            "org.springframework.boot.restclient.autoconfigure.",
            "org.springframework.boot.webclient.autoconfigure.",
            "org.springframework.boot.reactor.autoconfigure.",
            "org.springframework.boot.transaction.autoconfigure.",
            "org.springframework.boot.transaction.jta.autoconfigure.",
            "org.springframework.boot.data.autoconfigure.",
            "org.springdoc.");

    public static Optional<ZeusCapability> sahipBul(String autoconfigSinifAdi) {
        return HEPSI.stream().filter(y -> y.sahiplenir(autoconfigSinifAdi)).findFirst();
    }
}
