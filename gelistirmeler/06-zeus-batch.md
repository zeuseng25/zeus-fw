# 06 — zeus-batch

## Amaç

Toplu iş (batch) yeteneğini framework seviyesinde sunmak: Spring Batch tabanlı job/step altyapısı ve `JobLauncher` yardımcıları. Şu an `spring-wildfly-arch`'ta kullanılmıyor (hatta WildFly'da `batch-jberet` subsystem dışlanmış); ileride ihtiyaç duyan projeler için iskelet olarak hazırlanır.

## Mevcut Durum (iskelet)

- `com.zeus.framework.batch.ZeusBatchAutoConfiguration` (`@AutoConfiguration`) — yüklendiğini loglar.
- `.imports` ile otomatik yüklenir.
- Bağımlılıklar: `spring-boot-autoconfigure`, `spring-boot-starter-batch` (**optional** — yalnızca batch kullanan uygulama açıkça ekleyince devreye girer), `zeus-base`.

## Planlanan İçerik

- Ortak `Job` / `Step` yapı taşları ve `JobLauncher` sarmalayıcısı.
- Chunk/tasklet desenleri, okuyucu/işleyici/yazıcı (reader/processor/writer) iskeletleri.
- Job repository ve transaction yapılandırması (`zeus-database` ile uyumlu datasource üzerinden).
- WildFly notu: `batch-jberet` subsystem ile Spring Batch çakışmasını önleme rehberi.

## Kullanım

```xml
<dependency>
    <groupId>com.zeus</groupId>
    <artifactId>zeus-batch</artifactId>
</dependency>
<!-- optional starter transitive gelmez; batch kullanılıyorsa açıkça eklenir -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-batch</artifactId>
</dependency>
```

## İlgili

- `00-Genel-Mimari.md` · `01-zeus-base.md` · `03-zeus-database.md`
