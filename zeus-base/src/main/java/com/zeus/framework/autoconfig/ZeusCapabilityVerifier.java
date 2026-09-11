package com.zeus.framework.autoconfig;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;

/**
 * "Bağımlılık var ama property yok" ÇELİŞKİSİNİ açılışta yakalar.
 *
 * Bu, opt-in'den opt-out'a dönüş DEĞİLDİR: bildirilmemiş bir yetenek meşrudur (REST-only
 * uygulama hiçbir şey yazmaz ve hata almaz). Hata yalnız uygulama yetenek modülünü pom'una
 * yazmış AMA açmamışsa çıkar — yani niyet ile yapılandırma çeliştiğinde.
 *
 * İşaretçi sınıfın sinyal olmasının sebebi: install-zeus-module.sh'ın EXCLUDE_REGEX'i
 * 'zeus-[a-z0-9-]+' içerir, yani zeus jar'ları com.zeus module'üne GİRMEZ, WAR'da taşınır.
 * Dolayısıyla sınıfın classpath'te olması = uygulamanın o bağımlılığı kendi pom'una yazması.
 */
public class ZeusCapabilityVerifier implements EnvironmentPostProcessor {

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
            if (isaretciVar
                    && !ZeusAutoConfigurationFilter.booleanOzellikOku(environment, y.property(), false)) {
                hatalar.add(("'zeus-%s' bağımlılığı bu uygulamanın WAR'ında var ama '%s' yazılmamış. "
                        + "'%s' yeteneği KAPALI kalır ve bean'leri kurulmaz.%n"
                        + "      Kullanacaksanız application.properties'e ekleyin:  %s=true%n"
                        + "      Kullanmayacaksanız pom.xml'den zeus-%s bağımlılığını kaldırın.")
                        .formatted(y.ad(), y.property(), y.ad(), y.property(), y.ad()));
            }
        }
        return hatalar;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        List<String> hatalar = dogrula(environment, application.getClassLoader());
        if (!hatalar.isEmpty()) {
            throw new IllegalStateException("Zeus yetenek bildirimi eksik:%n      %s"
                    .formatted(String.join(System.lineSeparator() + "      ", hatalar)));
        }
    }
}
