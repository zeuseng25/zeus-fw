# CXF'in com.zeus'a taşınması + script sertleştirme — Uygulama Planı

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Steps use `- [ ]` checkboxes.

**Goal:** (1) CXF yığınını paylaşımlı `com.zeus` module'üne taşıyıp opt-in mekanizmasını tamamen kaldırmak; (2) hiçbir scriptin sessizce ölmemesini sağlamak.

**Architecture:** `zeus-wildfly-module` sözleşmesine CXF eklenir → üretilen dışlama listesi CXF'i otomatik kapsar → `com.zeus.soap` küme farkıyla boşalır (`install-zeus-module.sh:125-128`) → uygulama tarafındaki opt-in (`zeus.descriptor.extra.modules`), ikinci liste (`with-soap`) ve iki-property tutarlılık guard'ı gereksizleşip silinir. Sonuç: **tek üretilmiş liste, tek kural.**

**Spec:** `gelistirmeler/20-zeus-sms.md` (karar), `gelistirmeler/19-war-paketleme-module-farkindaligi.md` (paketleme kuralı)

## Global Constraints

- JDK 25: her `mvn` öncesi `export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home`
- Kod/yorum/doküman **Türkçe**; `.md` yalnız `zeus-fw`'ye.
- Uygulama reposunda (`../spring-wildfly-arch`) **ayrı commit**, `.md` yazılmaz.
- **Korunacak sertleştirmeler** (bu oturumda beş fix turunda kazanıldı): `check_ids_sane` tabanı ve orantılı küçülme eşiği, `mvn` çıkış kodu + boş çıktı kontrolü, stderr yalnız hata dallarında, `--check` yan etkisiz, atomik kurulum, marker-yok hard failure, argümansız çalıştırma = `--check`.
- Test scriptlerinin `set -uo pipefail` (`-e` YOK) tercihi **bilinçlidir** — hataları toplayıp sonunda raporlarlar. Onlara `-e` EKLEME.
- Sınır kuralı korunur: standart tip uygulamalar yalnız SOAP **istemcisi** olabilir; `@WebService` implementasyonu yayınlayan SOAP tipine geçmelidir.

---

### Task 1: Scriptler sessizce ölmesin

**Files:** `scripts/install-zeus-module.sh`, `scripts/verify-staging.sh`, `scripts/slot-inventory.sh`, `scripts/generate-war-excludes.sh` (yalnız ERR trap), test: `scripts/test-script-hardening.sh`

- [ ] **Step 1: Testi yaz** — `scripts/test-script-hardening.sh`:
  - `set -e` kullanan her script (`install-zeus-module.sh`, `verify-staging.sh`, `slot-inventory.sh`, `generate-war-excludes.sh`, `verify-module-coverage.sh`) bir `trap ... ERR` içermeli.
  - `install-zeus-module.sh`'ta kontrolsüz `mvn` çağrısı kalmamalı (her `mvn` ya `if !` ile sarılı ya da açık hata mesajı üreten bir yapıda).
  - Test, her script için tek tek ✅/❌ bassın ve ilk ❌'te `fail=1` yapıp sonunda non-zero dönsün.

- [ ] **Step 2: Testi çalıştır, KIRMIZI olduğunu gör** (hiçbirinde ERR trap yok).

- [ ] **Step 3: ERR trap'i ekle.** `set -euo pipefail` satırının hemen ardına:
  ```bash
  # Sessiz ölüm YASAK: set -e ile düşen her komut nerede düştüğünü söylesin.
  trap 'rc=$?; echo "HATA: ${BASH_SOURCE[0]}:${LINENO} — komut başarısız (çıkış ${rc}): ${BASH_COMMAND}" >&2' ERR
  ```
  Beş script için de. `run-guards.sh` ve `test-*.sh` **hariç** (bilinçli `-e`'siz).

- [ ] **Step 4: `install-zeus-module.sh`'taki üç `mvn` çağrısını sar** (`:101`, `:110`, `:145`) — her biri kendi hata mesajını versin (ne yapılmaya çalışıldığı + olası nedenler), sonra `exit 1`.

- [ ] **Step 5: `verify-staging.sh` (7) ve `slot-inventory.sh` (4) yutmalarını denetle.** Her `|| true` / `2>/dev/null` için karar ver: (a) gerçekten opsiyonel mi (ör. "dosya yoksa boş liste") → yorumla gerekçelendir; (b) hata gizliyor mu → açık kontrole çevir. Raporunda her birini tek tek gerekçelendir.

- [ ] **Step 6: Kanıt.** `install-zeus-module.sh`'ı `PATH`'te sahte başarısız `mvn` ile çalıştır (sunucuya yazmadan erken düşecek) → **açık hata mesajı** basmalı, sessiz ölmemeli. Öncesi/sonrası göster.

- [ ] **Step 7:** Test yeşil + `./scripts/run-guards.sh` yeşil → commit.

---

### Task 2: CXF'i com.zeus sözleşmesine taşı, opt-in mekanizmasını sil

**Files:** `zeus-wildfly-module/pom.xml`, `zeus-parent/pom.xml`, `zeus-war-defaults/src/main/resources/descriptor-{standard,soap}/jboss-deployment-structure.xml`, `scripts/generate-war-excludes.sh`, `scripts/verify-module-coverage.sh`, `../spring-wildfly-arch/pom.xml`, testler

- [ ] **Step 1:** `zeus-wildfly-module/pom.xml`'e CXF sözleşmesini ekle — `zeus-soap-wildfly-module`'ün bugün taşıdığı `cxf-spring-boot-starter-jaxws`. Yorumda gerekçe: *CXF artık paylaşımlı module'de; `com.zeus.soap` küme farkıyla boşalır; uygulama opt-in etmez.*

- [ ] **Step 2:** `./scripts/generate-war-excludes.sh --write` → standart liste CXF'i kapsamalı. **Doğrula:** `grep -c "cxf-core" zeus-parent/pom.xml` ≥ 1.

- [ ] **Step 3: Gereksizleşenleri sil.**
  - `zeus-parent/pom.xml`: `ZEUS-WAR-EXCLUDES-SOAP` bloğu (`zeus.war.packaging-excludes.with-soap`) ve `zeus.descriptor.extra.modules` property'si
  - `generate-war-excludes.sh`: `with-soap` üretimi ve ilgili render çağrısı (ikinci aşama); `list_soap` **kalır** (soap parent hâlâ kullanıyor)
  - `descriptor-standard` ve `descriptor-soap` şablonlarındaki `${zeus.descriptor.extra.modules}` yer tutucuları
  - `verify-module-coverage.sh`: iki-property tutarlılık kontrolü (`EXCL_SOAP`/`DESC_SOAP` ikilisi) — artık ifade edilemez bir tutarsızlığı denetliyor
  - `../spring-wildfly-arch/pom.xml`: iki satır (ayrı commit)

- [ ] **Step 4:** `mvn clean install` + `spring-wildfly-arch` build → WAR'da CXF **0**, `zeus-sms` **var**, descriptor'da `com.zeus.soap` **yok** (artık gerekmiyor).

- [ ] **Step 5:** `test-war-packaging.sh` tabanlığını gerçeğe uydur; **"CXF yok" iddiası korunur**.

- [ ] **Step 6:** Commit (framework + app ayrı).

---

### Task 3: Module'ü yenile ve iki yönlü eşitliği guard'la

**Files:** `scripts/verify-module-coverage.sh`, test: `scripts/test-module-liste-esitligi.sh`

- [ ] **Step 1:** `./scripts/install-zeus-module.sh` → `com.zeus` CXF'i alır (157 → ~180). Sonra `--module soap` → `com.zeus.soap` **küme farkıyla boşalmalı** (23 → ~0). İkisinin de jar sayısını raporla.
  > **`main` slot'u güncellendiği için WildFly RESTART şart.** Sunucuyu durdur, kur, yeniden başlat.

- [ ] **Step 2: YENİ GUARD** — `scripts/test-module-liste-esitligi.sh`: kurulu module dizini ile üretilen dışlama listesinin **iki yönlü** eşitliği.
  - `A \ B` (module'de var, listede yok) → **KIRMIZI**: "şu jar module'de ama dışlanmıyor; WAR'a da girer → çift kopya"
  - `B \ A` (listede var, module'de yok) → **KIRMIZI**: "dışlanıyor ama sunucuda yok → NoClassDefFoundError"
  - Sabit kuyruk (`ojdbc*`, `jakarta.*-api`, `tomcat-embed-*`, `lombok`, `jarmode`) muaf.
  - `mvn` düşerse/çıktı boşsa **hard failure** (sessiz yeşil YASAK).
  - `run-guards.sh`'a ekle.

- [ ] **Step 3: Kırılabilirliği kanıtla** — module dizinine sahte bir jar koy (`A \ B ≠ ∅`) → KIRMIZI; kaldır → YEŞİL. Sonra listeden bir girdiyi geçici çıkar (`B \ A ≠ ∅`) → KIRMIZI; geri al.

- [ ] **Step 4: Gerçek deploy** — `spring-wildfly-arch` ve `zeus-sample-soap` WildFly'a deploy; `ERROR`/`LinkageError`/`ClassCastException` yok; `/api/products` 200; SOAP `?wsdl` 200. Bitince WAR'ları kaldır, sunucuyu bulduğun gibi bırak.

---

### Task 4: Dokümanlar

- [ ] `gelistirmeler/20-zeus-sms.md` — opt-in mekanizması kalktı, CXF `com.zeus`'ta; ölçümlerle. Sınır kuralı **korunur**.
- [ ] `gelistirmeler/19-...md` — tek üretilmiş liste (ikinci blok kalktı); yeni iki yönlü guard.
- [ ] `gelistirmeler/08-...md` — "CXF ayrı module'e aittir" kuralı **değişti**; yeni gerekçe: ikinci module kurma zorunluluğundan kaçınmak. `com.zeus.soap`'ın artık boş olduğu.
- [ ] `CLAUDE.md` — modül tablosu ve module anlatımı.
- [ ] Ölçümler kendi koşularından; doğrulanamayana ✅ yazma.
