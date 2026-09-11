package com.zeus.framework.autoconfig;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 *
 * AYRICA (fix round 2): fiilen veto edilen her yetenek için açılışta TEK bir log satırı basılır
 * ({@link #vetolariRaporla}) — veto'nun imzası YOKLUK olduğu için, o satır olmadan vetonun
 * izini süren hiçbir iz kalmıyordu.
 */
public class ZeusAutoConfigurationFilter
        implements AutoConfigurationImportFilter, EnvironmentAware, BeanClassLoaderAware {

    private static final Logger log = LoggerFactory.getLogger(ZeusAutoConfigurationFilter.class);

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

    /**
     * Hakkında ZATEN log basılmış yetenekler (fix round 2, kusur 2). Veto BAŞINA değil,
     * YETENEK başına tek satır: {@code match()} birden çok kez çağrılsa da (Spring aday
     * listesini parça parça verebilir) aynı yetenek ikinci kez raporlanmaz.
     */
    private final Set<String> vetoLoglanan = new HashSet<>();

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
        // beanClassLoader null ise Spring'in KENDİ fallback'i uygulanır: Boot'un aynı
        // sınıfındaki AutoConfigurationImportSelector#checkExcludedClasses'ı da tam bu deseni
        // kullanır ((this.beanClassLoader != null) ? this.beanClassLoader : getClass().getClassLoader()).
        // (getConfigurationClassFilter() bu null kontrolünü YAPMAZ, beanClassLoader'ı olduğu gibi
        // geçirir — taklit edilen deseni checkExcludedClasses'tan alıyoruz, aynı sınıfın başka
        // bir metodundan.)
        // Bu sınıf zeus-base jar'ının İÇİNDE yaşar ve zeus-base her zaman WAR'ın WEB-INF/lib'inde
        // olduğundan (zeus jar'ları paylaşımlı com.zeus module'üne GİRMEZ), bu sınıfın kendi
        // classloader'ı da WAR'ın deployment classloader'ıdır — yani doğru yükleyicidir. Null'u
        // sessizce atlamak, mekanizmanın "denetim sessizce çalışmıyor" kusurunu (bkz.
        // ZeusCapabilityVerifier javadoc'u) burada küçük ölçekte yeniden üretirdi.
        if (!celiskiDenetlendi) {
            celiskiDenetlendi = true;
            ClassLoader kullanilacakYukleyici =
                    (beanClassLoader != null) ? beanClassLoader : getClass().getClassLoader();
            ZeusCapabilityVerifier.denetle(environment, kullanilacakYukleyici);
        }

        // Kaçış kapısı 2: adı verilen autoconfig'ler veto edilmez (yanlış sınıflandırma kurtarması).
        List<String> zorlaDahil = List.of(
                environment.getProperty("zeus.autoconfig.force-include", String[].class, new String[0]));

        // Yetenek başına veto sayısı; döngüden SONRA tek satır hâlinde raporlanır.
        Map<ZeusCapability, Integer> vetoSayaci = new LinkedHashMap<>();

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
            if (!sonuc[i]) {
                vetoSayaci.merge(sahip.get(), 1, Integer::sum);
            }
        }
        vetolariRaporla(vetoSayaci);
        return sonuc;
    }

    /**
     * Veto edilen HER yetenek için TEK bir log satırı basar (fix round 2, kusur 2).
     *
     * NEDEN VAR: veto'nun imzası YOKLUKTUR — veto edilen autoconfig sınıfı aday listesine hiç
     * girmez, dolayısıyla {@code debug=true} raporunda da görünmez ("Exclusions: None" yazar).
     * Bu satır olmadan, kendi pom'una JDBC/JPA koyup {@code zeus.database.enabled} yazmayan bir
     * uygulamanın operatörü yalnızca "No qualifying bean of type 'JdbcTemplate'" görürdü —
     * içinde "zeus" kelimesi HİÇ geçmeyen bir hata. Artık açılış logunda yetenek adı, property
     * adı ve veto sayısı yazılıdır.
     *
     * Bu bir VETO EKLEMEZ, yalnız var olan vetoyu görünür kılar: fail-open kuralı korunur
     * (sahipsiz sınıf ne veto edilir ne raporlanır).
     */
    private void vetolariRaporla(Map<ZeusCapability, Integer> vetoSayaci) {
        for (Map.Entry<ZeusCapability, Integer> girdi : vetoSayaci.entrySet()) {
            ZeusCapability yetenek = girdi.getKey();
            if (!vetoLoglanan.add(yetenek.ad())) {
                continue;   // bu yetenek için satır zaten basıldı
            }
            // İki farklı sebep, iki farklı cümle: property hiç yazılmamış olabilir ya da
            // BİLİNÇLİ olarak kapatılmış olabilir. Operatör hangisi olduğunu logdan görmeli.
            String bildirim = environment.containsProperty(yetenek.property())
                    ? "%s=%s".formatted(yetenek.property(), environment.getProperty(yetenek.property()))
                    : "%s yazılmamış".formatted(yetenek.property());
            log.info("Zeus: '{}' yeteneği KAPALI ({}) — {} autoconfig veto edildi. Açmak için: {}=true",
                    yetenek.ad(), bildirim, girdi.getValue(), yetenek.property());
        }
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
