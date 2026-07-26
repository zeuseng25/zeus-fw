package com.zeus.framework.redis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;

/**
 * Zeus Redis modülü auto-configuration kancası.
 *
 * <p>İskelet aşamasında yalnızca yüklendiğini loglar; hedeflenen içerik:
 * RedisTemplate yapılandırması ve cache (@Cacheable) soyutlaması.
 */
@AutoConfiguration
public class ZeusRedisAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ZeusRedisAutoConfiguration.class);

    public ZeusRedisAutoConfiguration() {
        log.info("Zeus Redis modülü yüklendi (iskelet).");
    }

    // TODO: RedisTemplate + CacheManager + ortak serileştirme yapılandırması.
}
