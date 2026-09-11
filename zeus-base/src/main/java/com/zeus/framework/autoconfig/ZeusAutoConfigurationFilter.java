package com.zeus.framework.autoconfig;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.AutoConfigurationImportFilter;
import org.springframework.boot.autoconfigure.AutoConfigurationMetadata;
import org.springframework.context.EnvironmentAware;
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
        if (!environment.getProperty("zeus.autoconfig.filter.enabled", Boolean.class, true)) {
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
                    || environment.getProperty(sahip.get().property(), Boolean.class, false);
        }
        return sonuc;
    }
}
