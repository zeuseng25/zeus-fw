package com.zeus.framework.autoconfig;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.AutoConfigurationImportFilter;
import org.springframework.boot.autoconfigure.AutoConfigurationMetadata;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.convert.ConversionFailedException;
import org.springframework.core.env.Environment;

/**
 * Yeteneğe ait 3. parti autoconfig'leri, uygulama o yeteneği açıkça istemedikçe eler.
 *
 * Spring'in RESMÎ uzantı noktasıdır: Boot'un kendi OnClassCondition / OnBeanCondition /
 * OnWebApplicationCondition sınıfları da aynı kancayla (META-INF/spring.factories) kayıtlıdır.
 * Context kurulmadan ÖNCE çalışır; maliyeti ad karşılaştırmasıdır.
 *
 * FAIL-OPEN: tanımadığı sınıfı veto ETMEZ. Framework'ün eksik bir sınıflandırması üretimi
 * düşürmemeli — sınıflandırma eksiğini build zamanında guard yakalar.
 */
public class ZeusAutoConfigurationFilter implements AutoConfigurationImportFilter, EnvironmentAware {

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public boolean[] match(String[] autoConfigurationClasses, AutoConfigurationMetadata metadata) {
        boolean[] sonuc = new boolean[autoConfigurationClasses.length];

        // Kaçış kapısı 1: mekanizmayı tamamen kapat (bu değişiklik öncesi davranış).
        if (!booleanOzellikOku("zeus.autoconfig.filter.enabled", true)) {
            Arrays.fill(sonuc, true);
            return sonuc;
        }

        // Kaçış kapısı 2: adı verilen autoconfig'ler veto edilmez (yanlış sınıflandırma kurtarması).
        List<String> zorlaDahil = List.of(
                environment.getProperty("zeus.autoconfig.force-include", String[].class, new String[0]));

        for (int i = 0; i < autoConfigurationClasses.length; i++) {
            String sinif = autoConfigurationClasses[i];
            // Spring bu diziye null koyabilir (zaten elenmiş adaylar) — dokunma.
            if (sinif == null) {
                sonuc[i] = true;
                continue;
            }
            if (zorlaDahil.contains(sinif)) {
                sonuc[i] = true;
                continue;
            }
            Optional<ZeusCapability> sahip = ZeusCapabilities.sahipBul(sinif);
            sonuc[i] = sahip.isEmpty()
                    || booleanOzellikOku(sahip.get().property(), false);
        }
        return sonuc;
    }

    /**
     * Bir boolean property'yi okur; typo'lu/tanımadık bir değer varsa (ör. "tru") anlaşılır bir
     * hatayla fail eder.
     *
     * NOT (bu ayrımı basitleştirmeye çalışma): burada uygulanan davranış, sınıfın başındaki
     * FAIL-OPEN kuralıyla ÇELİŞMEZ, ondan AYRIDIR. FAIL-OPEN, framework'ün TANIMADIĞI bir
     * autoconfig sınıfı içindir — sınıflandırmadaki bir boşluk, çalışan bir uygulamayı asla
     * kırmamalı. Ama burada söz konusu olan uygulamanın KENDİ yapılandırma hatasıdır (typo'lu bir
     * boolean); bunu sessizce false'a çevirip yeteneği kapatmak, hatayı gizler ve sorunu
     * ilerideki "eksik bean" gibi anlaşılmaz bir hataya öteler. O yüzden burada fail-open
     * UYGULANMAZ: hata, property adını ve verilen değeri belirterek açıkça fırlatılır.
     */
    private boolean booleanOzellikOku(String anahtar, boolean varsayilan) {
        try {
            return environment.getProperty(anahtar, Boolean.class, varsayilan);
        } catch (ConversionFailedException e) {
            throw new IllegalStateException(
                    ("Geçersiz yapılandırma: '%s' özelliği '%s' değerini kabul etmiyor. "
                            + "Kabul edilen değerler: true/false (TRUE/FALSE), 1/0, yes/no, on/off "
                            + "veya boş (varsayılan kullanılır).")
                            .formatted(anahtar, environment.getProperty(anahtar)),
                    e);
        }
    }
}
