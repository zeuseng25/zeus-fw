# 07 — BOM + Parent + Sürüm Yönetimi

## Amaç

Framework'ün **sürüm ve build altyapısını** tanımlayan üç POM bileşeni: `zeus-fw` (kök/aggregator), `zeus-dependencies` (BOM) ve `zeus-parent` (parent). Spring Boot'taki `spring-boot-dependencies` + `spring-boot-starter-parent` ikilisinin birebir karşılığı. Hedef: **tek noktadan sürüm yönetimi** ve tüketen uygulamalar için hazır plugin yapılandırması.

## Bileşenler

### 1) `zeus-fw` (kök, packaging=pom)
- Parent: `spring-boot-starter-parent:3.1.3` (Spring/Hibernate/Jackson/logging sürümleri + plugin yönetimi buradan).
- `<modules>`: 8 alt modülü toplayan aggregator.
- **Tek sürüm property'si:** `<revision>1.0.0-SNAPSHOT</revision>` ve `<spring-framework.version>6.0.11</spring-framework.version>`.
- `<build><plugins>`: **flatten-maven-plugin** (tüm alt modüller miras alır).

### 2) `zeus-dependencies` (BOM, packaging=pom)
- **Tek sürüm kaynağı.** `<dependencyManagement>` içinde:
  - `spring-boot-dependencies:3.1.3` (import) → BOM tek başına alındığında da Spring yönetimi gelir.
  - 6 zeus modülü (`${revision}`).
  - Spring Boot dışı 3. parti sürümler: `springdoc-openapi 2.2.0`, `ojdbc11 23.4.0.24.05`.
- Dışarıdan `scope=import` ile alınabilir.

### 3) `zeus-parent` (parent, packaging=pom)
- Uygulamaların ve zeus modüllerinin parent'ı.
- `<dependencyManagement>` → `zeus-dependencies` BOM import'u (sürüm yazmadan zeus-* + 3. parti kullanımı).
- `<pluginManagement>`:
  - `maven-war-plugin` → **ince WAR politikası**: `%regex[WEB-INF/lib/(?!zeus-).*\.jar]` (zeus-* WAR'da, 3. parti hariç).
  - `maven-compiler-plugin` → Lombok annotation processor yolu.
  - `spring-boot-maven-plugin` → `repackage` skip (fat WAR yok; `spring-boot:run` açık).

## Tek Versiyon Mekanizması (`${revision}` + flatten)

`${revision}` Maven'ın CI-friendly versioning yöntemidir. Sorun: install/deploy edilen pom'da `${revision}` ifadesi **literal** kalır; dışarıdaki tüketici property'yi göremediğinden çözemez.

**Çözüm:** `flatten-maven-plugin` `resolveCiFriendliesOnly` modu — yayınlanan pom'da yalnızca `${revision}`/`${sha1}`/`${changelist}`'i çözer, pom'un geri kalanını (dependencyManagement/pluginManagement) **aynen korur**. Kökün `<build><plugins>`'inde tanımlı olduğundan 8 modülün hepsi (pom ve jar) miras alır.

```xml
<properties>
    <revision>1.0.0-SNAPSHOT</revision>   <!-- TEK değiştirilecek yer -->
</properties>
```

Yan etki: `zeus-parent`'ı parent alan **tüketen uygulamalar da** flatten'ı miras alır ve `.flattened-pom.xml` üretir. Uygulama sürümü somut olduğundan bu **zararsızdır** (flatten no-op); dosya `.gitignore`'a eklenir.

## Bir Uygulama Nasıl Bağlanır?

```xml
<parent>
    <groupId>com.zeus</groupId>
    <artifactId>zeus-parent</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <relativePath/>
</parent>
<!-- modüller — sürümsüz -->
<dependency><groupId>com.zeus</groupId><artifactId>zeus-database</artifactId></dependency>
```

## Sürüm Yükseltme

- **Zeus sürümü:** kök `pom.xml`'de `<revision>`'ı değiştir → `./maven.sh clean install`.
- **Spring Boot sürümü:** kök parent `<version>` + `zeus-dependencies`'teki `spring-boot-dependencies` import sürümü.
- **3. parti (springdoc/ojdbc):** `zeus-dependencies` property'leri.

## İlgili

- `00-Genel-Mimari.md` · `01-zeus-base.md` … `06-zeus-batch.md`
- `../spring-wildfly-arch/gelistirmeler/13-zeus-framework-entegrasyonu.md`
