# 04 — zeus-service

## Amaç

Servis katmanı konvansiyonlarını standartlaştırmak: transaction yönetimi, interface + impl ayrımı, DTO ↔ model dönüşümü. Projeler aynı iş kuralları iskeletini yeniden yazmaz.

## Mevcut Durum

- `DtoMapper<E, REQ, RES>` (interface) — DTO ↔ domain dönüşüm sözleşmesi: `toEntity(req)`, `toResponse(entity)`, `toResponseList(...)` (default).
- `AbstractCrudService<E, ID, REQ, RES>` — ortak CRUD iş mantığı tabanı:
  - `findAll` / `findById` / `create` / `update` / `delete` public metotları hazır gelir.
  - Sınıf düzeyinde `@Transactional`; okuma metotları `readOnly=true`.
  - Bulunamayan kayıtta `ResourceNotFoundException` (zeus-base handler'ı 404 ProblemDetail'e çevirir).
  - Alt sınıf yalnızca veri erişim kancalarını (`doFindAll/doFindById/doCreate/doUpdate/doDelete`) ve `mapper()`'ı sağlar; `notFoundMessage(id)`'i override edebilir.
- `ZeusServiceAutoConfiguration` — kaydedilecek bean yok (sınıflar extend/implement edilir); yalnızca yüklenmeyi işaretler.

Bağımlılıklar: `spring-boot-autoconfigure`, `spring-tx`, `zeus-base`.

> `spring-wildfly-arch`'ın `ProductServiceImpl`'i artık `AbstractCrudService`'i extend ediyor, `ProductMapper` ise `DtoMapper`'ı implement ediyor (bkz. `13-zeus-framework-entegrasyonu.md`).

## Planlanan İçerik

- Sayfalama/filtreleme destekli base service varyantı.
- İsteğe bağlı, generic `EntityManager` tabanlı CRUD repository köprüsü.

## Kullanım

```xml
<dependency>
    <groupId>com.zeus</groupId>
    <artifactId>zeus-service</artifactId>
</dependency>
```

## İlgili

- `00-Genel-Mimari.md` · `01-zeus-base.md` · `03-zeus-database.md`
- `spring-wildfly-arch/gelistirmeler/04-katmanli-mimari.md`
