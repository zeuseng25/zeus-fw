# 05 — zeus-redis

## Amaç

Dağıtık önbellek (Redis) yeteneğini framework seviyesinde sunmak: hazır `RedisTemplate` ve cache soyutlaması. Şu an `spring-wildfly-arch`'ta kullanılmıyor; ileride ihtiyaç duyan projeler için iskelet olarak hazırlanır.

## Mevcut Durum (iskelet)

- `com.zeus.framework.redis.ZeusRedisAutoConfiguration` (`@AutoConfiguration`) — yüklendiğini loglar.
- `.imports` ile otomatik yüklenir.
- Bağımlılıklar: `spring-boot-autoconfigure`, `spring-boot-starter-data-redis` (**optional** — yalnızca redis kullanan uygulama açıkça ekleyince devreye girer), `zeus-base`.

## Planlanan İçerik

- `RedisTemplate` / `StringRedisTemplate` ortak yapılandırması (serializer seçimi: JSON/string).
- Spring Cache soyutlaması (`@EnableCaching`, `CacheManager`) ve ortak TTL/anahtar politikaları.
- Bağlantı yapılandırması (host/port/şifre) için ortak property önekleri.

## Kullanım

```xml
<dependency>
    <groupId>com.zeus</groupId>
    <artifactId>zeus-redis</artifactId>
</dependency>
<!-- optional starter transitive gelmez; redis kullanılıyorsa açıkça eklenir -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

## İlgili

- `00-Genel-Mimari.md` · `01-zeus-base.md`
