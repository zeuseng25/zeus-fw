# 08 — WildFly Paylaşımlı Module Dağıtımı (`zeus-wildfly-module` + install scripti)

Bu doküman, WildFly'daki **tek ve paylaşımlı `com.zeus` module**'ünün nasıl üretildiğini ve
neden bu sorumluluğun **platform'a (zeus-fw)** ait olduğunu tanımlar.

## Neden burada (zeus-fw), uygulamada değil

3. parti kütüphaneler (Spring, Spring Boot, Hibernate, Jackson, ...) hiçbir uygulamanın WAR'ına
konmaz; WildFly'daki **tek `com.zeus` module**'üne konur ve onu kullanan **tüm uygulamalar** oradan
yüklenir (ince WAR mimarisi — bkz. `spring-wildfly-arch/gelistirmeler/08-com-zeus-module.md`).

Module paylaşımlı olduğundan, onu üreten iş de **tek ve merkezi** olmalıdır — her uygulamaya
kopyalanmamalı. Bu yüzden hem bağımlılık sözleşmesi hem üretim scripti zeus-fw'dadır:

| Bileşen | Yer | Görev |
|---------|-----|-------|
| `zeus-wildfly-module` (pom modülü) | `zeus-fw/zeus-wildfly-module/` | Module'e girecek 3. parti runtime bağımlılıklarının **birleşimi** (union). |
| `install-zeus-module.sh` | `zeus-fw/scripts/` | Bu modülün runtime kapanışını çözüp `com.zeus` module'ünü üretir. |
| `deploy.sh` | her uygulamada (`<app>/scripts/`) | Yalnız o uygulamanın ince WAR'ını build + deploy eder (per-app, doğru). |

## Sorumluluk Sınırı (Governance) — kim ne yapabilir

Uygulamalar **yalnızca tüketicidir**: WildFly'a **sadece kendi ince WAR'larını deploy ederler**.
Module ile ilgili her şey framework'e (platform) aittir.

| Yetenek | Sahip | Nasıl zorlanır |
|---------|-------|----------------|
| WAR'dan 3. parti jar'ı dışlama (ince WAR) | **Framework** | `zeus-parent` `maven-war-plugin` `packagingExcludes` (`%regex[WEB-INF/lib/(?!zeus-).*\.jar]`) → uygulama configsiz miras alır; `zeus-` ile başlamayan **hiçbir** jar WAR'a giremez. |
| Paylaşımlı `com.zeus` module'ünü oluşturma/güncelleme | **Framework** | `zeus-fw/scripts/install-zeus-module.sh` yalnızca bu repodadır; uygulamalarda module üretim aracı **yoktur**. |
| Module'e jar ekleme | **Framework** | İçerik `zeus-wildfly-module` bağımlılık sözleşmesinden gelir; uygulama `modules/`'a yazmaz. |
| WAR deploy | **Uygulama** | Her uygulamanın kendi `scripts/deploy.sh`'ı yalnızca `standalone/deployments/`'a kopyalar. |

**Kural:** Uygulamalar `maven-war-plugin` yapılandırmasını **override etmemeli** (fat WAR yasak) ve
WildFly `modules/` dizinine **dokunmamalıdır**. Bu sınır bugün yapısal olarak sağlanır (uygulamada
module aracı yok, WAR dışlama framework'ten miras). İstenirse CI'da sert kontrol (örn. uygulama
pom'unda `maven-war-plugin` `<configuration>` varsa veya WAR'da zeus-dışı jar bulunursa build'i
kır) ek bir güvence olarak eklenebilir.

### App-specific dependency (WAR-bundle istisnası)

Bir uygulama, **framework'te/paylaşımlı module'de olmayan** ve **yalnızca kendi kullandığı** bir
kütüphaneye ihtiyaç duyabilir. İki seçenek vardır:

| Kütüphane türü | Nereye | Nasıl |
|----------------|--------|-------|
| **Genel/paylaşılan** (≥2 app) | Paylaşımlı `com.zeus` module | `zeus-wildfly-module/pom.xml`'e ekle → module'ü yeniden üret + restart. |
| **App'e özel** (yalnız 1 app) | O app'in **kendi WAR'ı** (WEB-INF/lib) | App pom'unda `${zeus.war.keep}` property'sini set et. |

**Neden module'e değil WAR'a:** App'e özel lib paylaşımlı module'e konursa **diğer tüm uygulamalara
dayatılır** (gereksiz şişme + lockstep yükü). WAR'a konunca yalnız o app'i etkiler. WildFly'da çalışır
çünkü app kodu + `WEB-INF/lib/<lib>` **aynı WAR classloader'ındadır** (kısıt yalnızca "module sınıfları
WAR'ı göremez"; app-specific lib'i sadece app kodu kullandığından sorun değil).

**Kullanım** — app pom'u (war-plugin'i **override etmeden**, sadece property):
```xml
<properties>
    <!-- foo-*.jar ve bar-*.jar bu app'in WAR'ında kalsın (module'e GİRMEZ) -->
    <zeus.war.keep>|foo-|bar-</zeus.war.keep>
</properties>
```
`zeus-parent`'taki regex `%regex[WEB-INF/lib/(?!(zeus-${zeus.war.keep})).*\.jar]` bu önekleri dışlama
dışı bırakır. **Varsayılan boş** → davranış değişmez (yalnız `zeus-*` WAR'da kalır). Önek, jar adının
başıyla eşleşmeli (ör. `foo-1.0.jar` için `|foo-`).

> Uyarı: `${zeus.war.keep}`'e koyduğun lib **module'e girmediği** için, onu **module'deki bir sınıf
> kullanamaz** (module → WAR görünmez). Yalnız app'in kendi kodu kullanabilir. Module'deki Spring/Hibernate
> gibi bir bileşenin görmesi gereken bir lib ise → o, app-specific değildir; `zeus-wildfly-module`'e konmalı.

### Module kapsam kontrolü (deploy guard) — sessiz tuzağı erkene çeker

`zeus-dependencies` BOM ~1000+ lib'in **sürümünü** yönetir; bir app bunlardan birini sürümsüz ekleyince
**derlenir ve `local` profilde çalışır**, ama lib `zeus-wildfly-module`'de (dolayısıyla module'de) yoksa ve
`zeus.war.keep` ile WAR'a da konmamışsa **WildFly'da `NoClassDefFoundError`** olur — genelde deploy anında,
kriptik bir hatayla. Bu uçurumu deploy'dan ÖNCE yakalamak için:

- **`zeus-fw/scripts/verify-module-coverage.sh <app-dir>`** — app'in runtime bağımlılık kapanışını
  (`dependency:list`) alır; `zeus-*`, WildFly'ın verdiği `jakarta.*-api` ve `zeus.war.keep` öneklerini
  düşer; geriye kalan her jar `modules/com/zeus/main/`'de var mı diye bakar. Eksik varsa **non-zero exit**
  ve net çözüm mesajı (zeus-wildfly-module'e ekle **veya** zeus.war.keep) verir.
- Her uygulamanın **`deploy.sh`'ı bu kontrolü WAR'ı kopyalamadan önce çağırır** (module kuruluysa). Kapsam
  dışı dep varsa deploy hiç başlamaz. `SKIP_COVERAGE=1` ile atlanabilir.

Böylece "BOM'da var → her yerde çalışır" yanılgısı, geç ve kriptik bir runtime hatası yerine erken ve
çözümü söyleyen bir deploy hatasına dönüşür; aynı zamanda `zeus-wildfly-module`'ün app'lerin gerisinde
kalmasının (drift) otomatik güvenlik ağıdır.

## `zeus-wildfly-module` — bağımlılık sözleşmesi

`packaging=pom`, parent `zeus-parent`. Jar üretmez; sadece **module'e girecek bağımlılıkları**
declare eder. Sürümler zeus BOM'dan gelir, burada yazılmaz.

**ÖNEMLİ kural:** Bu modül, `com.zeus` module'ünü paylaşan **tüm uygulamaların** runtime 3. parti
bağımlılıklarının **birleşimini** içermelidir. Yeni bir uygulama burada olmayan bir runtime
kütüphanesi kullanıyorsa, o bağımlılık önce buraya eklenir, sonra module yeniden üretilir; aksi
halde o uygulama WildFly'da `NoClassDefFoundError` alır. (Bugün tek tüketen `spring-wildfly-arch`
olduğundan içerik onun runtime starter'larını yansıtır: web, validation, data-jpa, springdoc;
gömülü Tomcat `provided` ile dışlanır — WildFly Undertow kullanır.)

## Üretim — `scripts/install-zeus-module.sh`

```bash
cd zeus-fw
./scripts/install-zeus-module.sh                                  # varsayılan WILDFLY_HOME, slot: main
./scripts/install-zeus-module.sh --slot 1.1.0                     # versiyonlu slot (immutable; restart'sız kurulum)
WILDFLY_HOME=/path/staging-wildfly ./scripts/install-zeus-module.sh   # staging hedefi
```

> **Versiyonlu slot'lar:** sürüm/CVE geçişlerinde lockstep'i ve restart zorunluluğunu kırmak için
> module sürüm başına ayrı slot'a kurulabilir (`modules/com/zeus/<slot>/`); uygulamalar üretilen
> descriptor'larındaki slot ile bağlanır. Detay: `10-versiyonlu-slot-uretilen-descriptor.md`.

Akış: `dependency:copy-dependencies` (zeus-wildfly-module, runtime scope) → jar'ları
`${WILDFLY_HOME}/modules/com/zeus/main/`'e kopyala (jakarta-api / lombok / jarmode / **zeus-***
dışlanır) → her jar'a Jandex index göm → `module.xml` üret.

- **Hedef sunucu `WILDFLY_HOME` ile seçilir** → aynı script staging ve prod'a karşı çalışır.
- **module.xml değişince WildFly RESTART şart** (module tanımı cache'li).
- Yalnız zeus/uygulama kodu değişince module değişmez (zeus jar'ları WAR'da) → WAR redeploy yeter.

## CVE yamasıyla ilişki

CVE yaması bu module üzerinden uygulanır: sürüm/override **`zeus-dependencies` BOM**'da yapılır,
sonra bu script module'ü yeniden üretir. Tek module = tek Spring sürümü = tüm uygulamalar lockstep.
Süreç (staging geçidi, gate'ler, rollback): `gelistirmeler/09-cve-guvenlik-yamalama.md`.

## Yeni uygulama eklenince yapılacaklar

1. Uygulamanın runtime 3. parti bağımlılıklarından `zeus-wildfly-module`'de **olmayan** var mı kontrol et.
2. Varsa `zeus-wildfly-module/pom.xml`'e ekle.
3. `install-zeus-module.sh` ile module'ü yeniden üret + WildFly restart.
4. Uygulamanın `deploy.sh`'ı ile WAR'ı deploy et.