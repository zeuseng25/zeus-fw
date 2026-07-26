# 01 — zeus-base

## Amaç

Framework'ün temel modülü. Tüm diğer zeus modüllerinin bağımlı olduğu çekirdek: ortak hata yönetimi (exception + RFC 7807 `ProblemDetail`) ve paylaşılan çekirdek tipler burada yaşar.

## Mevcut Durum

- `ResourceNotFoundException` — ortak "kayıt bulunamadı" istisnası (RuntimeException).
- `GlobalExceptionHandler` (`@RestControllerAdvice`) — istisnaları RFC 7807 `ProblemDetail`'e çevirir (not-found → 404, validation `MethodArgumentNotValidException` → 400 + alan hataları map'i).
- `ZeusBaseAutoConfiguration` — web uygulamalarında `GlobalExceptionHandler`'ı **bean** olarak kaydeder (`@ConditionalOnWebApplication` + `@ConditionalOnMissingBean`; uygulama kendi handler'ını verirse devreye girmez). Uygulamanın `com.zeus.framework.base` paketini bileşen taraması yapmasına gerek yoktur.
- `.imports` ile otomatik yüklenir. Bağımlılıklar: `spring-boot-autoconfigure`, `spring-web`, `slf4j-api`, `lombok` (optional).
- `slf4j-api` buradan **transitive** olarak diğer modüllere yayılır.

> `spring-wildfly-arch` bu sınıfları artık kendi içinde tutmuyor; `zeus-base`'ten kullanıyor (bkz. `13-zeus-framework-entegrasyonu.md`).

## Planlanan İçerik

- Genişletilmiş exception hiyerarşisi (`ZeusException` tabanı) ve ek handler'lar (ör. genel 500).
- Ortak yanıt/temel DTO tipleri (gerekirse).

## Kullanım

```xml
<dependency>
    <groupId>com.zeus</groupId>
    <artifactId>zeus-base</artifactId>
</dependency>
```

## İlgili

- `00-Genel-Mimari.md`
- `spring-wildfly-arch/gelistirmeler/04-katmanli-mimari.md` (exception/ProblemDetail kaynağı)
