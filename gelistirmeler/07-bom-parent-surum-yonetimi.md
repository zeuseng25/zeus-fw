# 07 — BOM + Parent + Sürüm Yönetimi

## Amaç

Framework'ün **sürüm ve build altyapısını** tanımlayan üç POM bileşeni: `zeus-fw` (kök/aggregator), `zeus-dependencies` (BOM) ve `zeus-parent` (parent). Spring Boot'taki `spring-boot-dependencies` + `spring-boot-starter-parent` ikilisinin birebir karşılığı. Hedef: **tek noktadan sürüm yönetimi** ve tüketen uygulamalar için hazır plugin yapılandırması.

## Bileşenler

### 1) `zeus-fw` (kök, packaging=pom)
- Parent: `spring-boot-starter-parent:4.0.7` (Spring/Hibernate/Jackson/logging sürümleri + plugin yönetimi buradan).
- `<modules>`: 18 alt modülü toplayan aggregator.
- **Tek sürüm property'si:** `<revision>2.0.0-SNAPSHOT</revision>`.
  `spring-framework.version` **pin'lenmez** — Boot 4 BOM'u Framework 7.0.x'i yönetir.
- `oracle-database.version` (Oracle ailesi) **burada** tanımlıdır; nedeni aşağıdaki
  "Boot'un yönettiği bir sürüm nasıl ezilir" bölümünde.
- `<build><plugins>`: **flatten-maven-plugin** (tüm alt modüller miras alır).

### 2) `zeus-dependencies` (BOM, packaging=pom)
- **Tek sürüm kaynağı.** `<dependencyManagement>` içinde:
  - BOM import'ları (sıra anlamlıdır — ilk import kazanır):
    `spring-boot-dependencies:4.0.7` → `spring-cloud-dependencies` → `spring-ai-bom`
    → `cxf-bom` → `shedlock-bom`.
  - 10 zeus modülü (`${revision}`).
  - Hiçbir BOM'un yönetmediği 3. parti sürümler (springdoc, poi, jasperreports, openpdf,
    jfreechart, commons-*, bouncycastle, spring-security-rsa, Oracle ailesi …).
- Kapsam kararları ve "neyi yönetmeyiz": `16-eol-bagimliliklar-ve-migrasyon.md`.
- Dışarıdan `scope=import` ile alınabilir.

#### Boot'un yönettiği bir sürüm nasıl ezilir (ÖNEMLİ tuzak)

Spring Boot BOM'u bir artefaktı zaten yönetiyorsa (ör. Oracle ailesi, Groovy), o yönetim
`spring-boot-starter-parent` üzerinden **parent zinciriyle miras alınır**.
`zeus-dependencies`'e yazılan bir giriş ise **import** yoluyla gelir ve miras alınana
**kaybeder** — yani sessizce etkisiz kalır.

Boot parent'ken tek işleyen yol: **Boot'un kendi property'sini aynı adla kök pom'da
yeniden tanımlamak.** Oracle sürücüsü bu yüzden `zeus-dependencies`'te değil,
`zeus-fw/pom.xml`'de `oracle-database.version` olarak durur.

Doğrulama refleksi — bir sürümü ezdikten sonra tüketen uygulamada:

```bash
mvn dependency:tree -Dincludes=<groupId>:<artifactId>
```

### 3) `zeus-parent` (parent, packaging=pom)
- Uygulamaların ve zeus modüllerinin parent'ı.
- `<dependencyManagement>` → `zeus-dependencies` BOM import'u (sürüm yazmadan zeus-* + 3. parti kullanımı).
- `<dependencies>` → `spring-boot-starter-test` (**test** scope) — aşağıdaki "Test altyapısı".
- `<pluginManagement>`:
  - `maven-war-plugin` → **ince WAR politikası**: `${zeus.war.packaging-excludes}`. Bu property'nin
    değeri **ÜRETİLİR** (`scripts/generate-war-excludes.sh`), paylaşımlı module'ün bağımlılık
    sözleşmesinden. Polarite **denylist**'tir: *module'ün verdiğini at, kalanı WAR'da taşı*.
    (Eski `%regex[WEB-INF/lib/(?!zeus-).*\.jar]` allowlist'i KALDIRILDI — module'de olmayan
    bağımlılığı da siliyordu; bkz. `19-war-paketleme-module-farkindaligi.md`.)
    `zeus-soap-parent` için ikinci bir liste (`com.zeus ∪ com.zeus.soap`, 193 alternatif) ve
    `zeus-parent`'ta opt-in edenler için `zeus.war.packaging-excludes.with-soap` aynı üreteçten gelir.
  - `maven-compiler-plugin` → Lombok annotation processor yolu.
  - `spring-boot-maven-plugin` → `repackage` skip (fat WAR yok; `spring-boot:run` açık).

## Test Altyapısı — `zeus-parent`'ta, uygulamada değil

Uygulamalar `spring-boot-starter-test` **yazmaz**; `zeus-parent` test scope'unda verir.
Bu bir kolaylık değil, **sessiz hata koruması**dır.

### Neden: surefire sessizce JUnit3'e düşer

Surefire provider'ını test classpath'ini tarayarak seçer. JUnit Platform motorunu
(`junit-jupiter-engine`) bulamazsa son çare olarak `JUnit3Provider`'a düşer — ve
**build kırılmaz**. JUnit 5 anotasyonlarını tanımadığı için testleri görmez,
`Tests run: 0` yazıp yeşil geçer.

Kontrollü deneyle üretildi (test sınıfı var, JUnit bağımlılığı yok):

```
[INFO] Using auto detected provider org.apache.maven.surefire.junit.JUnit3Provider
[INFO] Tests run: 1, Failures: 0, Errors: 0
[INFO] BUILD SUCCESS          ← sahte "test" isim kuralıyla geçmiş sayıldı
```

`zeus-parent`'a bağımlılık eklendikten sonra aynı modül:

```
[INFO] Using auto detected provider org.apache.maven.surefire.junitplatform.JUnitPlatformProvider
[INFO] Tests run: 0           ← doğru: o sınıf gerçekten test değil
```

Hiç test çalıştırmayan yeşil bir build, kırmızı bir build'den çok daha tehlikelidir —
50+ uygulamalı bir platformda "testlerimiz geçiyor" sanılırken hiç koşmuyor olabilir.

### Mockito javaagent (Java 25 hazırlığı)

`pluginManagement`'taki `maven-surefire-plugin` Mockito'yu javaagent olarak takar:

```xml
<argLine>@{argLine} -javaagent:${org.mockito:mockito-core:jar}</argLine>
```

Java 25'te Mockito inline-mock-maker kendini JVM'e **self-attach** ediyor ve
*"This will no longer work in future releases of the JDK"* uyarısı basıyor. JDK
self-attach'i kapattığı gün, önlem alınmazsa tüm uygulamaların testleri birden kırılır.

Üç incelik:
- `${org.mockito:mockito-core:jar}` property'sini `maven-dependency-plugin`'in
  `properties` goal'ü üretir; bu yüzden o eklenti `zeus-parent`'ın
  `<build><plugins>`'inde **devreye alınmıştır** (pluginManagement tek başına çalıştırmaz).
- Mockito her modüle yukarıdaki `spring-boot-starter-test` ile geldiği için property
  daima çözülür.
- `@{argLine}` geç bağlamadır: bir uygulama JaCoCo eklerse `prepare-agent`'ın doldurduğu
  değerin üstüne eklenir, ezmez. Tabanı `<properties>`'teki boş `<argLine>`'dır —
  o property hiç tanımlı olmazsa surefire ifadeyi literal bırakır ve JVM açılmaz.

## Tek Versiyon Mekanizması (`${revision}` + flatten)

`${revision}` Maven'ın CI-friendly versioning yöntemidir. Sorun: install/deploy edilen pom'da `${revision}` ifadesi **literal** kalır; dışarıdaki tüketici property'yi göremediğinden çözemez.

**Çözüm:** `flatten-maven-plugin` `resolveCiFriendliesOnly` modu — yayınlanan pom'da yalnızca `${revision}`/`${sha1}`/`${changelist}`'i çözer, pom'un geri kalanını (dependencyManagement/pluginManagement) **aynen korur**. Kökün `<build><plugins>`'inde tanımlı olduğundan 18 modülün hepsi (pom ve jar) miras alır.

```xml
<properties>
    <revision>2.0.0-SNAPSHOT</revision>   <!-- TEK değiştirilecek yer -->
</properties>
```

Yan etki: `zeus-parent`'ı parent alan **tüketen uygulamalar da** flatten'ı miras alır ve `.flattened-pom.xml` üretir. Uygulama sürümü somut olduğundan bu **zararsızdır** (flatten no-op); dosya `.gitignore`'a eklenir.

## Bir Uygulama Nasıl Bağlanır?

```xml
<parent>
    <groupId>com.zeus</groupId>
    <artifactId>zeus-parent</artifactId>
    <version>2.0.0-SNAPSHOT</version>
    <relativePath/>
</parent>
<!-- modüller — sürümsüz -->
<dependency><groupId>com.zeus</groupId><artifactId>zeus-database</artifactId></dependency>
```

## Sürüm Yükseltme

- **Zeus sürümü:** kök `pom.xml`'de `<revision>`'ı değiştir → `mvn clean install`.
- **Spring Boot sürümü:** kök parent `<version>` + `zeus-dependencies`'teki `spring-boot-dependencies` import sürümü.
- **3. parti (springdoc, poi, bouncycastle …):** `zeus-dependencies` property'leri.
- **Boot'un zaten yönettiği bir şey (Oracle ailesi, Groovy …):** kök `pom.xml`'de Boot'un
  property'sini aynı adla ez (ör. `oracle-database.version`) — BOM'a yazmak etkisiz kalır.
- **Oracle sürücüsü ayrıca:** WildFly'ın `com.oracle.ojdbc` module'ü elle hizalanmalı
  (`17-module-yenileme-runbook.md`).

## İlgili

- `00-Genel-Mimari.md` · `01-zeus-base.md` … `06-zeus-batch.md`
- `../spring-wildfly-arch/gelistirmeler/13-zeus-framework-entegrasyonu.md`
