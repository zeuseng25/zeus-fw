package com.zeus.framework.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;

/**
 * Zeus Batch modülü auto-configuration kancası.
 *
 * <p>İskelet aşamasında yalnızca yüklendiğini loglar; hedeflenen içerik:
 * Spring Batch job/step altyapısı ve JobLauncher yardımcıları.
 */
@AutoConfiguration
public class ZeusBatchAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ZeusBatchAutoConfiguration.class);

    public ZeusBatchAutoConfiguration() {
        log.info("Zeus Batch modülü yüklendi (iskelet).");
    }

    // TODO: Job/Step yapı taşları + JobLauncher + ortak batch yapılandırması.
}
