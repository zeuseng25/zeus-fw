package com.zeus.framework.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;

/**
 * Zeus Service modülü auto-configuration.
 *
 * <p>Modül, uygulamaların extend/implement ettiği sınıflar sunar
 * ({@link AbstractCrudService}, {@link DtoMapper}); kaydedilecek bean yoktur.
 * Bu auto-configuration yalnızca modülün yüklendiğini işaretler.
 */
@AutoConfiguration
public class ZeusServiceAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ZeusServiceAutoConfiguration.class);

    public ZeusServiceAutoConfiguration() {
        log.info("Zeus Service modülü yüklendi.");
    }
}
