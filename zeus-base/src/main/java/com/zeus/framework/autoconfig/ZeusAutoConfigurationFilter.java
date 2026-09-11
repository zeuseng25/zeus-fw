package com.zeus.framework.autoconfig;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.BeanClassLoaderAware;
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
 *
 * AYRICA (fix round 1): "bağımlılık WAR'da var ama yetenek hiç bildirilmemiş" çelişkisinin
 * denetimi de BURADAN koşar ({@link ZeusCapabilityVerifier#denetle}). Gerekçe o sınıfın
 * javadoc'unda: denetimin ilk evi olan EnvironmentPostProcessor ince WAR modelinde HİÇ
 * çalışmıyordu; bu sınıf ise çalıştığı gerçek sunucuda kanıtlandı.
 */
public class ZeusAutoConfigurationFilter
        implements AutoConfigurationImportFilter, EnvironmentAware, BeanClassLoaderAware {

    private Environment environment;

    /**
     * Uygulamanın KENDİ classloader'ı. Spring bunu her filtre örneğine
     * {@code AutoConfigurationImportSelector.invokeAwareMethods(...)} ile verir ve değeri
     * selector'ın {@code beanClassLoader}'ıdır — yani filtreleri (dolayısıyla BU sınıfı)
     * {@code WEB-INF/lib}'de BULAN classloader. İnce WAR'da WAR'ın deployment classloader'ıdır;
     * paylaşımlı com.zeus module'ünün classloader'ı DEĞİLDİR. Yetenek işaretçi sınıfları
     * yalnız onunla doğru aranabilir.
     */
    private ClassLoader beanClassLoader;

    /** Çelişki denetimi uygulama başına BİR kez koşar; her filtrelenen sınıf için DEĞİL. */
    private boolean celiskiDenetlendi;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void setBeanClassLoader(ClassLoader classLoader) {
        this.beanClassLoader = classLoader;
    }

    @Override
    public boolean[] match(String[] autoConfigurationClasses, AutoConfigurationMetadata metadata) {
        boolean[] sonuc = new boolean[autoConfigurationClasses.length];

        // Kaçış kapısı 1: mekanizmayı tamamen kapat (bu değişiklik öncesi davranış).
        if (!booleanOzellikOku(environment, "zeus.autoconfig.filter.enabled", true)) {
            Arrays.fill(sonuc, true);
            return sonuc;
        }

        // Çelişki denetimi: kaçış kapısı 1'den SONRA (mekanizma kapalıysa denetim de susar),
        // aday döngüsünden ÖNCE. Uygulama başına bir kez; bayrak, match() aday listesiyle
        // birden çok kez çağrılsa bile denetimin tekrarlanmamasını sağlar.
        // beanClassLoader null ise denetim yapılmaz: o durumda ölçülecek bir WAR yoktur
        // (yalnız elle kurulan birim testi filtresinde olur; Spring gerçek koşuda filtreyi
        // KULLANMADAN ÖNCE invokeAwareMethods ile bu değeri HER ZAMAN set eder).
        if (!celiskiDenetlendi && beanClassLoader != null) {
            celiskiDenetlendi = true;
            ZeusCapabilityVerifier.denetle(environment, beanClassLoader);
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
                    || booleanOzellikOku(environment, sahip.get().property(), false);
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
     *
     * Paket-görünür (private DEĞİL): ZeusCapabilityVerifier de aynı okuma/hata davranışını
     * kullanır — typo'lu bir property iki bileşende de aynı şekilde ele alınsın diye.
     */
    static boolean booleanOzellikOku(Environment environment, String anahtar, boolean varsayilan) {
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
