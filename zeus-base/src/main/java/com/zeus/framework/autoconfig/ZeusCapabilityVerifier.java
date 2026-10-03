package com.zeus.framework.autoconfig;

import java.util.ArrayList;
import java.util.List;
import org.springframework.core.env.Environment;

/**
 * "Bağımlılık var ama property yok" ÇELİŞKİSİNİ açılışta yakalar.
 *
 * Bu, opt-in'den opt-out'a dönüş DEĞİLDİR: bildirilmemiş bir yetenek meşrudur (REST-only
 * uygulama hiçbir şey yazmaz ve hata almaz). Hata yalnız uygulama yetenek modülünü pom'una
 * yazmış AMA hiçbir şey söylememişse çıkar — yani niyet ile yapılandırma çeliştiğinde.
 * AÇIKÇA {@code false} yazmak bilinçli bir bildirimdir ve ÇELİŞKİ DEĞİLDİR (aşağıya bakın).
 *
 * İşaretçi sınıfın sinyal olmasının sebebi: install-zeus-module.sh'ın EXCLUDE_REGEX'i
 * 'zeus-[a-z0-9-]+' içerir, yani zeus jar'ları com.zeus module'üne GİRMEZ, WAR'da taşınır.
 * Dolayısıyla sınıfın classpath'te olması = uygulamanın o bağımlılığı kendi pom'una yazması.
 *
 * <h2>Neden ARTIK bir EnvironmentPostProcessor DEĞİL (fix round 1)</h2>
 *
 * İlk sürüm bu denetimi {@code META-INF/spring.factories}'e kayıtlı bir
 * {@code EnvironmentPostProcessor} olarak koşturuyordu. Gerçek sunucu deneyi bunun ÜRETİMDE
 * HİÇ ÇALIŞMADIĞINI kanıtladı: ince WAR modelinde {@code spring-boot} jar'ı paylaşımlı
 * {@code com.zeus} WildFly module'ündedir ve o classloader WAR'ın {@code WEB-INF/lib}'indeki
 * {@code META-INF/spring.factories} dosyalarını göremez — framework bu kısıtı
 * {@code ZeusServletInitializer} javadoc'unda zaten belgelemişti.
 * {@code spring-wildfly-arch}, {@code zeus.database.enabled} silinmiş hâlde SESSİZCE deploy
 * oldu; beklenen konuşan hata çıkmadı.
 *
 * Denetim bu yüzden {@link ZeusAutoConfigurationFilter}'a taşındı: o, Spring'in
 * {@code AutoConfigurationImportSelector}'ı tarafından BEAN classloader'ı ile yüklenir
 * (AutoConfigurationImportSelector#getAutoConfigurationImportFilters), yani WAR'ın kendi
 * deployment classloader'ı ile — ki bu, sınıfı {@code WEB-INF/lib}'de BULAN classloader'ın
 * ta kendisidir. Aynı yol hem gömülü çalıştırmada hem WildFly'da geçerlidir; böylece testte
 * görülen davranış ile üretimdeki davranış AYRIŞMAZ (bu ayrışma kusurun kendisiydi).
 *
 * Bu sınıf karar mantığını taşımaya devam eder ({@link #dogrula}); yalnız NEREDEN çağrıldığı
 * değişti.
 */
public final class ZeusCapabilityVerifier {

    private ZeusCapabilityVerifier() {
    }

    /**
     * Çelişkileri bulur; bulursa açılışı durduran {@link IllegalStateException} atar.
     * Çağıran: {@link ZeusAutoConfigurationFilter#match} (uygulama başına BİR kez).
     */
    static void denetle(Environment environment, ClassLoader classLoader) {
        List<String> hatalar = dogrula(environment, classLoader);
        if (!hatalar.isEmpty()) {
            throw new IllegalStateException("Zeus yetenek bildirimi eksik:%n      %s"
                    .formatted(String.join(System.lineSeparator() + "      ", hatalar)));
        }
    }

    static List<String> dogrula(Environment environment, ClassLoader classLoader) {
        List<String> hatalar = new ArrayList<>();
        // Kaçış kapısı tutarlı olmalı: mekanizma (ZeusAutoConfigurationFilter) kapalıysa
        // çelişki denetimi de susar — aynı boolean okuma/hata davranışı paylaşılır.
        if (!ZeusAutoConfigurationFilter.booleanOzellikOku(environment, "zeus.autoconfig.filter.enabled", true)) {
            return hatalar;
        }
        for (ZeusCapability y : ZeusCapabilities.HEPSI) {
            boolean isaretciVar;
            try {
                Class.forName(y.isaretciSinif(), false, classLoader);
                isaretciVar = true;
            } catch (ClassNotFoundException | LinkageError e) {
                isaretciVar = false;
            }
            if (!isaretciVar) {
                continue;
            }
            // ÇELİŞKİ YALNIZ PROPERTY'NİN YOKLUĞUDUR (fix round 1, kusur 2).
            // 'zeus.<yetenek>.enabled=false' BİLİNÇLİ bir bildirimdir: "bağımlılık WAR'da var
            // ama bu yeteneği istemiyorum" demenin tek yolu odur ve hata vermemelidir.
            // Önceki sürüm 'property != true' diye baktığı için bu cümleyi kurmak imkânsızdı.
            if (!environment.containsProperty(y.property())) {
                hatalar.add(("'zeus-%s' bağımlılığı bu uygulamanın WAR'ında var ama '%s' yazılmamış. "
                        + "'%s' yeteneği KAPALI kalır ve bean'leri kurulmaz.%n"
                        + "      Kullanacaksanız application.properties'e ekleyin:  %s=true%n"
                        + "      Kullanmayacaksanız pom.xml'den zeus-%s bağımlılığını kaldırın%n"
                        + "      (ya da bilinçli olarak kapalı tutmak için:  %s=false).")
                        .formatted(y.ad(), y.property(), y.ad(), y.property(), y.ad(), y.property()));
                continue;
            }
            // Bildirilmiş: değeri BURADA da doğrula ki typo'lu bir boolean (ör. "tru") yeteneğin
            // autoconfig'leri aday listesine hiç girmediği durumda bile sessiz kalmasın.
            ZeusAutoConfigurationFilter.booleanOzellikOku(environment, y.property(), false);
        }
        return hatalar;
    }
}
