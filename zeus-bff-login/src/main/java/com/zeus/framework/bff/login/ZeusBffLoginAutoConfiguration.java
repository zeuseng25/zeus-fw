package com.zeus.framework.bff.login;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;

/**
 * Zeus BFF Login modülü auto-configuration — İSKELET.
 *
 * <p>Hedef kapsam (kurum kimlik sağlayıcısı netleşince doldurulacak):
 * <ul>
 *   <li>Login/logout uç noktaları ve oturum (cookie) yönetimi,</li>
 *   <li>Route zincirine takılacak oturum doğrulama filter'ı ({@link LoginFilterHook}),</li>
 *   <li>Token değişimi/yenileme kancaları ({@link SessionHook}).</li>
 * </ul>
 * Şimdilik yalnızca uzantı noktalarını (hook arayüzleri) tanımlar; davranış eklemez.
 */
@AutoConfiguration
public class ZeusBffLoginAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ZeusBffLoginAutoConfiguration.class);

    public ZeusBffLoginAutoConfiguration() {
        log.info("Zeus BFF Login modülü yüklendi (iskelet).");
    }
}
