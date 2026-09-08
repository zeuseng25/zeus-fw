# WAR Paketlemesi: Module-Farkındalığı — Uygulama Planı

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** İnce WAR'ın jar dışlama kuralını allowlist'ten ("`zeus-` dışındakini at") denylist'e ("paylaşımlı module'ün verdiğini at") çevirmek; böylece module'de olmayan bağımlılıklar sessizce silinmek yerine WAR'da taşınsın.

**Architecture:** Dışlama listesi `zeus-wildfly-module` (SOAP için ek olarak `zeus-soap-wildfly-module`) runtime kapanışından bir shell scriptiyle üretilir ve `zeus-parent` / `zeus-soap-parent` POM'larına marker'lar arasına **yazılır**. Tüketim ucu değişmez — `maven-war-plugin` bugünkü `${zeus.war.packaging-excludes}` property'sini okumaya devam eder. Üretici `install-zeus-module.sh` içinden çağrılır, böylece module ile liste asla ayrışamaz.

**Tech Stack:** Bash, Maven 3.9 (`dependency:list`), `maven-war-plugin` `packagingExcludes`, Python 3 (POM marker değişimi), JDK 25.

**Spec:** `gelistirmeler/19-war-paketleme-module-farkindaligi.md`

## Global Constraints

- **JDK 25 zorunlu.** Her `mvn` komutundan önce: `export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home` (JDK 17 ile `release version 25 not supported` alınır).
- **Framework kökü:** `/Users/omer/workspaces/intellij/spring-wildfly-upgrade/zeus-fw`. Örnek uygulamalar kardeş dizinlerdedir: `../spring-wildfly-arch`, `../zeus-sample-soap`, `../zeus-sample-bff`, `../zeus-sample-standalone`.
- **WildFly:** `WF=/Users/omer/workspaces/intellij/wildfly-41/wildfly-41.0.0.Final`. `com.zeus:main` = 157 jar, `com.zeus.soap:main` = 23 jar.
- **Eşleştirme artifactId bazındadır, sürüm dahil değildir.** Aynı artifactId module'deyse sürüm farklı olsa bile WAR'a girmez.
- **Sabit kuyruk = `install-zeus-module.sh`'ın `EXCLUDE_REGEX`'i eksi `zeus-*`:** `ojdbc[0-9]+`, `orai18n`, `ucp[0-9]+`, `jakarta.*-api`, `lombok`, `spring-boot-jarmode-*`. Bunlar module'de değildir ama WAR'a da girmemelidir.
- **`zeus-*` jar'ları listeye ASLA girmez** — WAR'da taşınmaya devam ederler.
- **Fat WAR tipleri değişmez:** `zeus-bff-parent` ve `zeus-standalone-parent`'ta `zeus.war.packaging-excludes` boş kalır.
- **Yorumlar Türkçe**, çevredeki stille tutarlı (CLAUDE.md konvansiyonu).
- **Uyarı mekanizması EKLENMEZ** — bilinçli karar (spec §"Bilinçli kabul edilen taviz").

---

### Task 1: Üretici script — liste üretimi (stdout)

`scripts/generate-war-excludes.sh`, module kapanışından regex üretir ve stdout'a basar. Bu görevde POM'a **yazmaz**; yazma Task 2'de eklenir. Böylece üretim mantığı POM düzenlemesinden bağımsız doğrulanır.

**Files:**
- Create: `scripts/generate-war-excludes.sh`
- Test: `scripts/test-generate-war-excludes.sh`

**Interfaces:**
- Consumes: yok (ilk görev).
- Produces:
  - `generate-war-excludes.sh --print standard` → stdout'a tek satır: `%regex[WEB-INF/lib/(...)-[0-9][^/]*\.jar]`
  - `generate-war-excludes.sh --print soap` → aynı biçim, `com.zeus` ∪ `com.zeus.soap` kümesi
  - Task 2 bu iki çağrıyı kullanır.

- [ ] **Step 1: Testi yaz (önce başarısız olacak)**

`scripts/test-generate-war-excludes.sh`:

```bash
#!/usr/bin/env bash
# generate-war-excludes.sh çıktısının doğruluk testi.
set -uo pipefail
FW_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GEN="${FW_ROOT}/scripts/generate-war-excludes.sh"
fail=0
check() { # $1=aciklama $2=beklenen(0=var,1=yok) $3=desen $4=metin
    if grep -qE "$3" <<< "$4"; then found=0; else found=1; fi
    if [[ "${found}" == "$2" ]]; then echo "  ✅ $1"; else echo "  ❌ $1"; fail=1; fi
}

echo ">> standard listesi"
STD="$(${GEN} --print standard)" || { echo "❌ script hata verdi"; exit 1; }
check "spring-core VAR (module'de)"            0 '\(|\|)spring-core(\||\))'      "${STD}"
check "hibernate-core VAR (module'de)"         0 '\(|\|)hibernate-core(\||\))'   "${STD}"
check "zeus-base YOK (WAR'da taşınır)"         1 'zeus-base'                     "${STD}"
check "commons-io YOK (module'de değil)"       1 'commons-io'                    "${STD}"
check "ojdbc sabit kuyruğu VAR"                0 'ojdbc\[0-9\]\+'                "${STD}"
check "jakarta api sabit kuyruğu VAR"          0 'jakarta'                       "${STD}"
check "sürüm koruması -[0-9] VAR"              0 '\)-\[0-9\]'                    "${STD}"
check "%regex sarmalayıcı VAR"                 0 '^%regex\[WEB-INF/lib/'         "${STD}"

echo ">> soap listesi"
SOAP="$(${GEN} --print soap)" || { echo "❌ script hata verdi"; exit 1; }
check "cxf-core VAR (com.zeus.soap'ta)"        0 'cxf-core'                      "${SOAP}"
check "spring-core VAR (birleşim)"             0 '\(|\|)spring-core(\||\))'      "${SOAP}"
check "zeus-soap YOK (WAR'da taşınır)"         1 'zeus-soap'                     "${SOAP}"

echo ">> soap listesi standard'ın üst kümesi olmalı"
std_n=$(tr '|' '\n' <<< "${STD}"  | wc -l | tr -d ' ')
soap_n=$(tr '|' '\n' <<< "${SOAP}" | wc -l | tr -d ' ')
if (( soap_n > std_n )); then echo "  ✅ soap(${soap_n}) > standard(${std_n})"; else echo "  ❌ soap(${soap_n}) > standard(${std_n}) değil"; fail=1; fi

exit ${fail}
```

- [ ] **Step 2: Testi çalıştır, başarısız olduğunu gör**

```bash
chmod +x scripts/test-generate-war-excludes.sh
./scripts/test-generate-war-excludes.sh
```
Beklenen: `generate-war-excludes.sh: No such file or directory` → exit != 0.

- [ ] **Step 3: Scripti yaz**

`scripts/generate-war-excludes.sh`:

```bash
#!/usr/bin/env bash
#
# WAR dışlama listesi üreteci (PLATFORM scripti).
#
# İnce WAR'ın WEB-INF/lib'inden atılacak jar'ların regex'ini, paylaşımlı WildFly
# module'lerinin BAĞIMLILIK SÖZLEŞMESİNDEN üretir. Kural: "module'ün verdiğini at,
# kalan her şeyi WAR'da taşı" (denylist). Eskiden tersiydi ("zeus- dışındakini at")
# ve module'de olmayan bağımlılıklar sessizce siliniyordu → WildFly'da
# NoClassDefFoundError. Gerekçe: gelistirmeler/19-war-paketleme-module-farkindaligi.md
#
# Kullanım:
#   ./scripts/generate-war-excludes.sh --print standard   # regex'i stdout'a bas
#   ./scripts/generate-war-excludes.sh --print soap       # com.zeus ∪ com.zeus.soap
#   ./scripts/generate-war-excludes.sh --write            # iki parent POM'u güncelle
#   ./scripts/generate-war-excludes.sh --check            # POM'lar güncel mi (CI)
#
set -euo pipefail

FW_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MVN="${MVN:-mvn}"

# Module'de OLMAYAN ama WAR'a da GİRMEMESİ gereken küme.
# = install-zeus-module.sh'ın EXCLUDE_REGEX'i EKSİ zeus-* :
#   ojdbc/orai18n/ucp → WildFly'ın kendi com.oracle.ojdbc module'ünden gelir; WAR'daki
#     ikinci kopya JNDI Connection'ı ile tip ayrışması yaratır (ClassCastException).
#   jakarta.*-api     → WildFly server module'lerinden gelir; kopyası LinkageError üretir.
#   lombok/jarmode    → runtime'da işlevsiz, WAR'ı şişirir.
# zeus-* BU LİSTEDE YOKTUR: module'e girmez AMA WAR'da taşınır (tek istisna).
FIXED_TAIL='ojdbc[0-9]+|orai18n|ucp[0-9]+|jakarta\.[a-z.]+-api|lombok|spring-boot-jarmode-[a-z]+'

# Bir sözleşme modülünün runtime kapanışındaki artifactId'leri basar.
# install-zeus-module.sh module'ü ÜRETİRKEN aynı kaynağı (dependency, includeScope=runtime)
# kullanır — liste ile module'ün aynı kümeyi görmesi buna dayanır.
closure_artifact_ids() {
    local module_dir="$1" out
    out="$(mktemp)"
    ( cd "${FW_ROOT}/${module_dir}" \
      && ${MVN} -q -B -Dstyle.color=never dependency:list \
           -DincludeScope=runtime -DoutputFile="${out}" >/dev/null 2>&1 )
    # format: groupId:artifactId:jar[:classifier]:version:scope
    sed 's/\x1b\[[0-9;]*m//g; s/^[[:space:]]*//' "${out}" \
      | grep -E '^[^:]+:[^:]+:[^:]+:' \
      | awk -F: '{print $2}' \
      | grep -Ev '^zeus-[a-z0-9-]+$' \
      | sort -u
    rm -f "${out}"
}

# artifactId listesini %regex[...] ifadesine çevirir.
# -[0-9] koruması ŞART: alternation'da 'spring-boot' varken sürüm kontrolü olmadan
# 'spring-boot-custom-1.0.jar' de eşleşir ve module'de OLMAYAN bir jar yanlışlıkla atılırdı.
build_regex() {
    local ids alt
    ids="$(cat)"
    alt="$(sed 's/\./\\./g' <<< "${ids}" | paste -sd'|' -)"
    printf '%%regex[WEB-INF/lib/(%s|%s)-[0-9][^/]*\\.jar]\n' "${alt}" "${FIXED_TAIL}"
}

list_standard() { closure_artifact_ids zeus-wildfly-module | build_regex; }

list_soap() {
    # BİRLEŞİM: com.zeus ∪ com.zeus.soap. SOAP WAR'ına iki module'ün de içeriği girmemeli;
    # tek liste kullanılsa CXF yığını WAR'a girer ve com.zeus.soap ile çift kopya olurdu.
    { closure_artifact_ids zeus-wildfly-module
      closure_artifact_ids zeus-soap-wildfly-module; } | sort -u | build_regex
}

case "${1:---write}" in
    --print)
        case "${2:-standard}" in
            standard) list_standard ;;
            soap)     list_soap ;;
            *) echo "bilinmeyen liste: ${2}" >&2; exit 2 ;;
        esac
        ;;
    *) echo "bu adımda yalnız --print destekleniyor" >&2; exit 2 ;;
esac
```

- [ ] **Step 4: Testi çalıştır, geçtiğini gör**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
chmod +x scripts/generate-war-excludes.sh
./scripts/test-generate-war-excludes.sh
```
Beklenen: tüm satırlar ✅, exit 0.

- [ ] **Step 5: Commit**

```bash
git add scripts/generate-war-excludes.sh scripts/test-generate-war-excludes.sh
git commit -m "WAR dışlama listesi üreteci: module sözleşmesinden regex üretimi"
```

---

### Task 2: POM yazma + `zeus-parent`'ın denylist'e geçmesi

**Files:**
- Modify: `scripts/generate-war-excludes.sh` (`--write` / `--check` modları)
- Modify: `zeus-parent/pom.xml:82` (`zeus.war.packaging-excludes` → marker'lı üretilmiş blok)
- Test: `scripts/test-war-packaging.sh`

**Interfaces:**
- Consumes: Task 1'in `--print standard` / `--print soap` çıktısı.
- Produces:
  - `generate-war-excludes.sh --write` → `zeus-parent/pom.xml` ve `zeus-soap-parent/pom.xml` marker bloklarını günceller (SOAP marker'ı Task 3'te eklenir; yoksa o dosya sessizce atlanır).
  - `generate-war-excludes.sh --check` → POM'lar güncel değilse exit 1.
  - Marker formatı (Task 3 ve 6 buna dayanır):
    `<!-- ZEUS-WAR-EXCLUDES:BEGIN ... -->` … `<!-- ZEUS-WAR-EXCLUDES:END -->`

- [ ] **Step 1: Testi yaz**

`scripts/test-war-packaging.sh`:

```bash
#!/usr/bin/env bash
# WAR paketleme davranışının uçtan uca testi (gerçek build + gerçek WAR).
set -uo pipefail
FW_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP="${FW_ROOT}/../spring-wildfly-arch"
fail=0
say() { if [[ "$1" == 0 ]]; then echo "  ✅ $2"; else echo "  ❌ $2"; fail=1; fi }

libs() { unzip -l "$(ls -t "${APP}"/target/*.war | head -1)" \
         | awk '{print $4}' | grep '^WEB-INF/lib/.*\.jar$' | sed 's#WEB-INF/lib/##' | sort; }

echo ">> A) REGRESYON: mevcut app'in WAR içeriği DEĞİŞMEMELİ"
( cd "${APP}" && mvn -q clean package -DskipTests ) || { echo "❌ build"; exit 1; }
got="$(libs)"
expected="zeus-ai
zeus-base
zeus-database
zeus-logger
zeus-service"
got_names="$(sed 's/-2\.0\.0-SNAPSHOT\.jar$//' <<< "${got}" | sort)"
[[ "${got_names}" == "${expected}" ]] && say 0 "yalnız 5 zeus jar'ı" || { say 1 "yalnız 5 zeus jar'ı"; echo "--- gelen:"; echo "${got}"; }
grep -qE '^ojdbc' <<< "${got}" && say 1 "ojdbc WAR'da YOK" || say 0 "ojdbc WAR'da YOK"
grep -qE '^jakarta\.' <<< "${got}" && say 1 "jakarta api WAR'da YOK" || say 0 "jakarta api WAR'da YOK"

echo ">> B) YENİ DAVRANIŞ: module'de olmayan bağımlılık WAR'a GİRMELİ"
cp "${APP}/pom.xml" /tmp/wpt-pom.bak
python3 - "${APP}/pom.xml" <<'PY'
import io,sys
p=sys.argv[1]; s=io.open(p,encoding='utf-8').read()
a='    <dependencies>\n'
assert s.count(a)>=1
s=s.replace(a, a+"""        <dependency>
            <groupId>commons-io</groupId>
            <artifactId>commons-io</artifactId>
            <version>2.20.0</version>
        </dependency>
""",1)
io.open(p,'w',encoding='utf-8').write(s)
PY
( cd "${APP}" && mvn -q clean package -DskipTests ) || { echo "❌ build(B)"; }
libs | grep -q '^commons-io-' && say 0 "commons-io WAR'a girdi" || say 1 "commons-io WAR'a girdi"
cp /tmp/wpt-pom.bak "${APP}/pom.xml"
( cd "${APP}" && mvn -q clean package -DskipTests >/dev/null 2>&1 )

exit ${fail}
```

- [ ] **Step 2: Testi çalıştır, B bölümünün başarısız olduğunu gör**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
chmod +x scripts/test-war-packaging.sh
./scripts/test-war-packaging.sh
```
Beklenen: A bölümü ✅ (bugünkü allowlist zaten bunu sağlıyor), **B bölümü ❌** — `commons-io` allowlist tarafından atılıyor. exit != 0.

- [ ] **Step 3: `--write` / `--check` modlarını ekle**

`scripts/generate-war-excludes.sh` içindeki `case` bloğunu şununla değiştir:

```bash
BEGIN_MARK='<!-- ZEUS-WAR-EXCLUDES:BEGIN — ÜRETİLMİŞTİR, ELLE DÜZENLEMEYİN (scripts/generate-war-excludes.sh) -->'
END_MARK='<!-- ZEUS-WAR-EXCLUDES:END -->'

# POM'daki marker bloğunu yeni property ile değiştirir.
write_pom() {  # $1=pom yolu  $2=regex
    local pom="$1" regex="$2"
    [[ -f "${pom}" ]] || { echo ">> atlandı (yok): ${pom}"; return 0; }
    grep -q 'ZEUS-WAR-EXCLUDES:BEGIN' "${pom}" || { echo ">> atlandı (marker yok): ${pom}"; return 0; }
    BEGIN_MARK="${BEGIN_MARK}" END_MARK="${END_MARK}" REGEX="${regex}" python3 - "${pom}" <<'PY'
import io,os,re,sys
pom=sys.argv[1]; b=os.environ['BEGIN_MARK']; e=os.environ['END_MARK']; rx=os.environ['REGEX']
s=io.open(pom,encoding='utf-8').read()
i=s.index(b); j=s.index(e)+len(e)
indent=' '*8
block=(b+"\n"+indent+"<zeus.war.packaging-excludes>"+rx+"</zeus.war.packaging-excludes>\n"+indent+e)
io.open(pom,'w',encoding='utf-8').write(s[:i]+block+s[j:])
PY
    echo ">> güncellendi: ${pom}"
}

case "${1:---write}" in
    --print)
        case "${2:-standard}" in
            standard) list_standard ;;
            soap)     list_soap ;;
            *) echo "bilinmeyen liste: ${2}" >&2; exit 2 ;;
        esac
        ;;
    --write)
        write_pom "${FW_ROOT}/zeus-parent/pom.xml"      "$(list_standard)"
        write_pom "${FW_ROOT}/zeus-soap-parent/pom.xml" "$(list_soap)"
        ;;
    --check)
        tmp="$(mktemp -d)"; trap 'rm -rf "${tmp}"' EXIT
        cp "${FW_ROOT}/zeus-parent/pom.xml" "${tmp}/std.bak"
        cp "${FW_ROOT}/zeus-soap-parent/pom.xml" "${tmp}/soap.bak" 2>/dev/null || true
        write_pom "${FW_ROOT}/zeus-parent/pom.xml"      "$(list_standard)" >/dev/null
        write_pom "${FW_ROOT}/zeus-soap-parent/pom.xml" "$(list_soap)"     >/dev/null
        rc=0
        diff -q "${tmp}/std.bak" "${FW_ROOT}/zeus-parent/pom.xml" >/dev/null || rc=1
        if [[ -f "${tmp}/soap.bak" ]]; then
            diff -q "${tmp}/soap.bak" "${FW_ROOT}/zeus-soap-parent/pom.xml" >/dev/null || rc=1
        fi
        # POM'ları HER DURUMDA eski hâline döndür (--check yan etkisiz olmalı).
        # NOT: `[[ ... ]] && cmd` KULLANMAYIN — `set -e` altında test false dönerse
        # script oracıkta düşer ve POM'lar değiştirilmiş hâlde kalır.
        cp "${tmp}/std.bak" "${FW_ROOT}/zeus-parent/pom.xml"
        if [[ -f "${tmp}/soap.bak" ]]; then
            cp "${tmp}/soap.bak" "${FW_ROOT}/zeus-soap-parent/pom.xml"
        fi
        if [[ "${rc}" != 0 ]]; then
            echo "❌ Üretilmiş WAR dışlama listesi GÜNCEL DEĞİL. Çalıştırın: ./scripts/generate-war-excludes.sh --write" >&2
        else
            echo "✅ WAR dışlama listeleri module sözleşmeleriyle uyumlu."
        fi
        exit "${rc}"
        ;;
    *) echo "kullanım: $0 [--print standard|soap] [--write] [--check]" >&2; exit 2 ;;
esac
```

- [ ] **Step 4: `zeus-parent/pom.xml`'e marker bloğunu koy**

`zeus-parent/pom.xml:79-82`'deki mevcut yorum + property'yi şununla değiştir:

```xml
        <!-- WAR dışlama politikası — DENYLIST (TİP kararıdır, uygulama kararı değil).
             Kural: paylaşımlı com.zeus module'ünün SAĞLADIĞI jar'lar WAR'a konmaz; module'de
             OLMAYAN her şey WAR'da taşınır. Eskiden tersiydi (yalnız zeus-* kalırdı) ve
             module'de olmayan bağımlılıklar sessizce silinip WildFly'da NoClassDefFoundError
             üretiyordu — gerekçe: gelistirmeler/19-war-paketleme-module-farkindaligi.md.
             zeus-bff-parent ve zeus-standalone-parent bu property'yi BOŞALTIR (fat WAR).
             Aşağıdaki blok ÜRETİLİR; elle düzenlenmez. -->
        <!-- ZEUS-WAR-EXCLUDES:BEGIN — ÜRETİLMİŞTİR, ELLE DÜZENLEMEYİN (scripts/generate-war-excludes.sh) -->
        <zeus.war.packaging-excludes>PLACEHOLDER-ILK-URETIMDE-DOLDURULACAK</zeus.war.packaging-excludes>
        <!-- ZEUS-WAR-EXCLUDES:END -->
```

Sonra listeyi üret:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
./scripts/generate-war-excludes.sh --write
grep -c "ZEUS-WAR-EXCLUDES" zeus-parent/pom.xml   # beklenen: 2
grep -o "spring-core" zeus-parent/pom.xml | head -1   # beklenen: spring-core
mvn -q clean install -DskipTests && echo "BUILD OK"
```

- [ ] **Step 5: Testi çalıştır, A ve B'nin geçtiğini gör**

```bash
./scripts/test-war-packaging.sh
```
Beklenen: A bölümünde hâlâ yalnız 5 zeus jar'ı (**regresyon yok**), B bölümünde `commons-io` WAR'a girmiş. exit 0.

- [ ] **Step 6: `--check` modunu doğrula**

```bash
./scripts/generate-war-excludes.sh --check && echo "CHECK OK"
```
Beklenen: `✅ WAR dışlama listeleri module sözleşmeleriyle uyumlu.` exit 0.

- [ ] **Step 7: Commit**

```bash
git add scripts/generate-war-excludes.sh scripts/test-war-packaging.sh zeus-parent/pom.xml
git commit -m "zeus-parent: WAR dışlaması allowlist'ten denylist'e (üretilmiş liste)"
```

---

### Task 3: SOAP tipi — `com.zeus` ∪ `com.zeus.soap` listesi

**Files:**
- Modify: `zeus-soap-parent/pom.xml` (yeni marker'lı property bloğu)
- Test: `scripts/test-war-packaging-soap.sh`

**Interfaces:**
- Consumes: Task 2'nin `--write` modu ve marker formatı.
- Produces: SOAP tipi WAR'ların dışlama listesi. Sonraki görevler bunu değiştirmez.

- [ ] **Step 1: Testi yaz**

`scripts/test-war-packaging-soap.sh`:

```bash
#!/usr/bin/env bash
# SOAP tipi WAR: com.zeus VE com.zeus.soap içeriğinin hiçbiri WAR'a girmemeli.
set -uo pipefail
FW_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP="${FW_ROOT}/../zeus-sample-soap"
fail=0
say() { if [[ "$1" == 0 ]]; then echo "  ✅ $2"; else echo "  ❌ $2"; fail=1; fi }

( cd "${APP}" && mvn -q clean package -DskipTests ) || { echo "❌ build"; exit 1; }
libs="$(unzip -l "$(ls -t "${APP}"/target/*.war | head -1)" \
        | awk '{print $4}' | grep '^WEB-INF/lib/.*\.jar$' | sed 's#WEB-INF/lib/##' | sort)"
echo "${libs}"

grep -qE '^cxf-'      <<< "${libs}" && say 1 "CXF jar'ı WAR'da YOK"     || say 0 "CXF jar'ı WAR'da YOK"
grep -qE '^wsdl4j-'   <<< "${libs}" && say 1 "wsdl4j WAR'da YOK"        || say 0 "wsdl4j WAR'da YOK"
grep -qE '^spring-core-' <<< "${libs}" && say 1 "spring-core WAR'da YOK" || say 0 "spring-core WAR'da YOK"
grep -qE '^zeus-soap-' <<< "${libs}" && say 0 "zeus-soap WAR'da VAR"     || say 1 "zeus-soap WAR'da VAR"
grep -qE '^zeus-base-' <<< "${libs}" && say 0 "zeus-base WAR'da VAR"     || say 1 "zeus-base WAR'da VAR"

exit ${fail}
```

- [ ] **Step 2: Testi çalıştır, başarısız olduğunu gör**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
chmod +x scripts/test-war-packaging-soap.sh
./scripts/test-war-packaging-soap.sh
```
Beklenen: **CXF jar'ları WAR'da** → ❌. Sebep: `zeus-soap-parent` bugün `zeus-parent`'ın (yalnız `com.zeus` kapsayan) listesini miras alıyor; CXF o listede yok, dolayısıyla WAR'a giriyor. exit != 0.

- [ ] **Step 3: `zeus-soap-parent/pom.xml`'e marker bloğunu ekle**

`zeus-soap-parent/pom.xml`'deki `<properties>` bloğunun içine ekle:

```xml
        <!-- SOAP TİPİ WAR dışlaması — BİRLEŞİM: com.zeus ∪ com.zeus.soap.
             zeus-parent'ınkini MİRAS ALMAK YETMEZ: o yalnız com.zeus'u kapsar, CXF yığını
             (com.zeus.soap) WAR'a girer ve module ile ÇİFT KOPYA olur → LinkageError.
             Kural: iki module'den hiçbirinin içeriği WAR'a konmaz.
             Aşağıdaki blok ÜRETİLİR; elle düzenlenmez. -->
        <!-- ZEUS-WAR-EXCLUDES:BEGIN — ÜRETİLMİŞTİR, ELLE DÜZENLEMEYİN (scripts/generate-war-excludes.sh) -->
        <zeus.war.packaging-excludes>PLACEHOLDER-ILK-URETIMDE-DOLDURULACAK</zeus.war.packaging-excludes>
        <!-- ZEUS-WAR-EXCLUDES:END -->
```

Sonra üret ve kur:

```bash
./scripts/generate-war-excludes.sh --write
grep -c "ZEUS-WAR-EXCLUDES" zeus-soap-parent/pom.xml   # beklenen: 2
grep -o "cxf-core" zeus-soap-parent/pom.xml | head -1  # beklenen: cxf-core
mvn -q clean install -DskipTests && echo "BUILD OK"
```

- [ ] **Step 4: Testi çalıştır, geçtiğini gör**

```bash
./scripts/test-war-packaging-soap.sh
```
Beklenen: tüm satırlar ✅, exit 0.

- [ ] **Step 5: Standart tipin bozulmadığını doğrula**

```bash
./scripts/test-war-packaging.sh
```
Beklenen: exit 0 (Task 2'deki A ve B hâlâ geçiyor).

- [ ] **Step 6: Commit**

```bash
git add zeus-soap-parent/pom.xml scripts/test-war-packaging-soap.sh
git commit -m "zeus-soap-parent: com.zeus ∪ com.zeus.soap birleşim dışlama listesi"
```

---

### Task 4: `zeus.war.keep`'in kaldırılması

Varlık sebebi tam olarak kapatılan boşluktu: module'de olmayan bir lib'i WAR'da taşımak için elle yazılan öneki listesi. Artık gereksiz; hiçbir uygulama kullanmıyor (doğrulandı).

**Files:**
- Modify: `zeus-parent/pom.xml` (`zeus.war.keep` property'si ve yorumu)
- Modify: `scripts/verify-module-coverage.sh` (`KEEP` / `KEEP_PREFIXES` mantığı)
- Modify: `gelistirmeler/08-wildfly-module-dagitim.md` (`zeus.war.keep` geçen yerler)

**Interfaces:**
- Consumes: Task 2'nin denylist'i (artık `${zeus.war.keep}` regex'te kullanılmıyor).
- Produces: yok — sadeleştirme.

- [ ] **Step 1: Testi yaz (kullanım kalmadığının kanıtı)**

```bash
cat > scripts/test-no-war-keep.sh <<'EOF'
#!/usr/bin/env bash
# zeus.war.keep tamamen kaldırıldı mı?
set -uo pipefail
FW_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# YALNIZ KOD taranır (*.xml, *.sh). Dokümanlar property'nin KALDIRILDIĞINI anlatmak için
# adını anmaya devam eder — bu kalıntı değildir.
hits="$(grep -rn "zeus\.war\.keep" "${FW_ROOT}" \
        --include='*.xml' --include='*.sh' \
        2>/dev/null | grep -v '/target/' | grep -v '\.flattened-pom\.xml' || true)"
if [[ -z "${hits}" ]]; then echo "✅ zeus.war.keep kalıntısı yok"; exit 0; fi
echo "❌ kalıntı var:"; echo "${hits}"; exit 1
EOF
chmod +x scripts/test-no-war-keep.sh
```

- [ ] **Step 2: Testi çalıştır, başarısız olduğunu gör**

```bash
./scripts/test-no-war-keep.sh
```
Beklenen: `zeus-parent/pom.xml`, `scripts/verify-module-coverage.sh` ve `gelistirmeler/08-...md` satırları listelenir. exit 1.

- [ ] **Step 3: Üç dosyadan kaldır**

1. `zeus-parent/pom.xml` — `<zeus.war.keep/>` property'sini ve üstündeki 8 satırlık açıklama yorumunu sil.
2. `scripts/verify-module-coverage.sh` — `KEEP=`, `KEEP_PREFIXES=`, ve döngüdeki `zeus.war.keep ile WAR'a bundle edilenler → atla` bloğunu (`skip=0` … `[[ "${skip}" == 1 ]] && continue`) sil.
3. `gelistirmeler/08-wildfly-module-dagitim.md` — `zeus.war.keep` geçen paragrafları, denylist kuralını anlatacak şekilde yeniden yaz:

```markdown
> **App-specific bağımlılık.** Yalnız bir uygulamanın kullandığı ve paylaşımlı module'e
> konması gerekmeyen bir kütüphane, hiçbir şey yazılmadan **WAR'da taşınır**: dışlama
> listesi yalnız module'ün sağladığı jar'ları kapsar, gerisi otomatik WAR'a girer
> (`19-war-paketleme-module-farkindaligi.md`). Eskiden bunun için `zeus.war.keep` ile elle
> önek yazmak gerekiyordu; o property kaldırıldı.
```

- [ ] **Step 4: Testi ve build'i çalıştır**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
./scripts/test-no-war-keep.sh
mvn -q clean install -DskipTests && echo "BUILD OK"
./scripts/test-war-packaging.sh
```
Beklenen: üçü de exit 0.

- [ ] **Step 5: Commit**

```bash
git add zeus-parent/pom.xml scripts/verify-module-coverage.sh scripts/test-no-war-keep.sh gelistirmeler/08-wildfly-module-dagitim.md
git commit -m "zeus.war.keep kaldırıldı: denylist ile gereksizleşti"
```

---

### Task 5: `verify-module-coverage.sh` — yanlış pozitif dalın kaldırılması

Script bugün "bağımlılık module'de yoksa deploy'u durdur" diyor. Denylist'ten sonra o bağımlılık WAR'da taşınacağı için deploy'u durdurmak **yanlış**. Slot-kurulu-mu ve üretilmiş-descriptor kontrolleri kalır — ikisi de hâlâ gerçek hataları yakalar.

**Files:**
- Modify: `scripts/verify-module-coverage.sh`
- Test: `scripts/test-coverage-guard.sh`

**Interfaces:**
- Consumes: Task 4'te `KEEP` mantığı zaten kaldırılmış olacak.
- Produces: script artık yalnız iki kontrolü yapar; `deploy.sh` çağrıları değişmeden çalışır.

- [ ] **Step 1: Testi yaz**

```bash
cat > scripts/test-coverage-guard.sh <<'EOF'
#!/usr/bin/env bash
# Module'de OLMAYAN bir bağımlılık artık deploy'u durdurmamalı.
set -uo pipefail
FW_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP="${FW_ROOT}/../spring-wildfly-arch"
cp "${APP}/pom.xml" /tmp/cg-pom.bak
python3 - "${APP}/pom.xml" <<'PY'
import io,sys
p=sys.argv[1]; s=io.open(p,encoding='utf-8').read(); a='    <dependencies>\n'
s=s.replace(a, a+"""        <dependency>
            <groupId>commons-io</groupId>
            <artifactId>commons-io</artifactId>
            <version>2.20.0</version>
        </dependency>
""",1)
io.open(p,'w',encoding='utf-8').write(s)
PY
"${FW_ROOT}/scripts/verify-module-coverage.sh" "${APP}"; rc=$?
cp /tmp/cg-pom.bak "${APP}/pom.xml"
if [[ "${rc}" == 0 ]]; then echo "✅ module'de olmayan bağımlılık deploy'u durdurmuyor"; exit 0; fi
echo "❌ script hâlâ exit ${rc} veriyor (yanlış pozitif)"; exit 1
EOF
chmod +x scripts/test-coverage-guard.sh
```

- [ ] **Step 2: Testi çalıştır, başarısız olduğunu gör**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
./scripts/test-coverage-guard.sh
```
Beklenen: `❌ MODULE KAPSAM HATASI: … commons-io …` → exit 1.

- [ ] **Step 3: Eksik-bağımlılık dalını kaldır**

`scripts/verify-module-coverage.sh` içinde:
- `EXCLUDE_REGEX` tanımını, `TMP`/`deps.txt` üretimini, `missing=()` döngüsünü ve `if [[ ${#missing[@]} -gt 0 ]]` bloğunu tamamen sil.
- Dosya başındaki açıklama bloğunu güncelle:

```bash
# Deploy ön-kontrolü (PLATFORM scripti).
#
# İKİ ŞEYİ denetler:
#   1) Uygulamanın hedeflediği com.zeus slot'u sunucuda KURULU mu?
#   2) WAR'da framework'ün ÜRETTİĞİ jboss-deployment-structure.xml var mı?
#
# NOT: "bağımlılık module'de var mı?" kontrolü KALDIRILDI. Denylist paketlemesinden sonra
# module'de olmayan bağımlılık WAR'da taşınır (gelistirmeler/19-war-paketleme-module-
# farkindaligi.md); onu eksik saymak yanlış pozitiftir.
```
- Sonunda başarı mesajını değiştir: `echo "✅ Slot kurulu ve üretilmiş descriptor yerinde."`

- [ ] **Step 4: Testleri çalıştır**

```bash
./scripts/test-coverage-guard.sh
./scripts/verify-module-coverage.sh ../spring-wildfly-arch && echo "NORMAL AKIS OK"
```
Beklenen: ikisi de exit 0.

- [ ] **Step 5: Commit**

```bash
git add scripts/verify-module-coverage.sh scripts/test-coverage-guard.sh
git commit -m "verify-module-coverage: eksik-bağımlılık dalı kaldırıldı (yanlış pozitif)"
```

---

### Task 6: `install-zeus-module.sh` üreteci çağırsın (drift'i imkânsız kıl)

**Files:**
- Modify: `scripts/install-zeus-module.sh` (sona üretici çağrısı)
- Test: `scripts/test-generator-wiring.sh`

**Interfaces:**
- Consumes: Task 2'nin `--write` ve `--check` modları.
- Produces: module üretimi ile liste üretimi tek komutta bağlanır.

- [ ] **Step 1: Testi yaz**

```bash
cat > scripts/test-generator-wiring.sh <<'EOF'
#!/usr/bin/env bash
# install-zeus-module.sh üreteci çağırıyor mu, ve listeler güncel mi?
set -uo pipefail
FW_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
fail=0
grep -q "generate-war-excludes.sh" "${FW_ROOT}/scripts/install-zeus-module.sh" \
  && echo "  ✅ install-zeus-module.sh üreteci çağırıyor" \
  || { echo "  ❌ install-zeus-module.sh üreteci çağırmıyor"; fail=1; }
"${FW_ROOT}/scripts/generate-war-excludes.sh" --check \
  && echo "  ✅ listeler güncel" || { echo "  ❌ listeler güncel değil"; fail=1; }
exit ${fail}
EOF
chmod +x scripts/test-generator-wiring.sh
```

- [ ] **Step 2: Testi çalıştır, ilk kontrolün başarısız olduğunu gör**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
./scripts/test-generator-wiring.sh
```
Beklenen: `❌ install-zeus-module.sh üreteci çağırmıyor`, exit 1.

- [ ] **Step 3: `install-zeus-module.sh` sonuna ekle**

Dosyanın en sonuna (başarı mesajından hemen önce):

```bash
# WAR dışlama listelerini AYNI kapanıştan yeniden üret.
# Module ve liste tek komuttan çıktığı için ayrışmaları (drift) imkânsızdır; bu yüzden
# ayrı bir senkron denetimi yoktur. Atlamak için: ZEUS_SKIP_WAR_EXCLUDES=1
if [[ "${ZEUS_SKIP_WAR_EXCLUDES:-0}" != "1" ]]; then
    echo ">> WAR dışlama listeleri yeniden üretiliyor (zeus-parent + zeus-soap-parent)..."
    "$(dirname "${BASH_SOURCE[0]}")/generate-war-excludes.sh" --write
fi
```

- [ ] **Step 4: Testi çalıştır**

```bash
./scripts/test-generator-wiring.sh
```
Beklenen: iki satır da ✅, exit 0.

- [ ] **Step 5: Commit**

```bash
git add scripts/install-zeus-module.sh scripts/test-generator-wiring.sh
git commit -m "install-zeus-module: WAR dışlama listelerini aynı kapanıştan üretir"
```

---

### Task 7: Dokümantasyon + uçtan uca doğrulama

**Files:**
- Modify: `gelistirmeler/19-war-paketleme-module-farkindaligi.md` (durum: uygulandı + ölçümler)
- Modify: `gelistirmeler/10-versiyonlu-slot-uretilen-descriptor.md` (üretilen ikinci artefakt)
- Modify: `gelistirmeler/17-module-yenileme-runbook.md` (runbook adımı)
- Modify: `CLAUDE.md` (Build bölümü — üretici komutu)

**Interfaces:**
- Consumes: Task 1-6'nın tamamı.
- Produces: yok — kapanış.

- [ ] **Step 1: Tüm testleri sırayla çalıştır**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
cd /Users/omer/workspaces/intellij/spring-wildfly-upgrade/zeus-fw
mvn -q clean install && echo "FRAMEWORK BUILD OK"
for t in test-generate-war-excludes test-war-packaging test-war-packaging-soap \
         test-no-war-keep test-coverage-guard test-generator-wiring; do
    echo "=== ${t} ==="; ./scripts/${t}.sh || echo "!!! ${t} BAŞARISIZ"
done
```
Beklenen: hepsi ✅.

- [ ] **Step 2: Fat WAR tiplerinin değişmediğini doğrula**

```bash
for app in zeus-sample-bff zeus-sample-standalone; do
    ( cd "../${app}" && mvn -q clean package -DskipTests )
    n=$(unzip -l ../${app}/target/*.war | grep -c 'WEB-INF/lib/.*\.jar')
    echo "${app}: ${n} jar (fat WAR — çok olmalı, >100)"
done
```
Beklenen: ikisi de yüzlerce jar — `zeus.war.packaging-excludes` boş kaldığı için davranış değişmemiş.

- [ ] **Step 3: WildFly'a gerçek deploy + smoke**

```bash
export WILDFLY_HOME=/Users/omer/workspaces/intellij/wildfly-41/wildfly-41.0.0.Final
"${WILDFLY_HOME}/bin/standalone.sh" > /tmp/wf.log 2>&1 &
until grep -q "WFLYSRV0025" /tmp/wf.log; do sleep 2; done
cp ../spring-wildfly-arch/target/*.war "${WILDFLY_HOME}/standalone/deployments/"
sleep 20
grep -E "WFLYSRV0016|WFLYSRV0010|ERROR" "${WILDFLY_HOME}/standalone/log/server.log" | tail -5
```
Beklenen: `WFLYSRV0010: Deployed` , `ERROR` yok.

- [ ] **Step 4: `test-project--service` doğrulaması (BAŞKA MAKİNEDE — elle)**

Bu uygulama bu workspace'te yok; Windows'taki geliştirme makinesinde duruyor
(`D:/dvl_ij/...`). Spec'in doğrulama tablosundaki bu satır **burada kapatılamaz**;
o makinede şu adımlarla doğrulanmalı ve sonucu dokümana işlenmeli:

```bash
# 1) yeni zeus-parent'ı al ve build et
cd /d/dvl_ij/test-project--service
mvn clean package -DskipTests

# 2) bugün silinen jar'lar artık WAR'da olmalı
unzip -l target/*.war | grep 'WEB-INF/lib/' | grep -v 'zeus-' | head -20
#    beklenen: backend-framework-security-starter ve arkadaşları listede

# 3) ojdbc HÂLÂ girmemeli (sabit kuyruk çalışıyor mu)
unzip -l target/*.war | grep -c 'WEB-INF/lib/ojdbc'    # beklenen: 0

# 4) deploy
cp target/*.war "$WILDFLY_HOME/standalone/deployments/"
grep -E "WFLYSRV0010|ERROR|NoClassDefFound" "$WILDFLY_HOME/standalone/log/server.log" | tail
```

Sonuç alınana kadar Task 7 **kapatılmaz**; alınamıyorsa doküman 19'da bu satır
"doğrulanmadı — erişim yok" olarak açıkça işaretlenir (sessizce ✅ yazılmaz).

- [ ] **Step 5: Doküman 19'u "uygulandı" durumuna geçir**

`gelistirmeler/19-war-paketleme-module-farkindaligi.md` başındaki durum satırını değiştir:

```markdown
**Durum:** UYGULANDI (2026-09-08). Tasarım kararları ve ölçümler aşağıda; uygulama planı
`docs/superpowers/plans/2026-09-08-war-paketleme-module-farkindaligi.md`.
```

"Doğrulama planı" tablosunun her satırına gerçek sonucu (✅ + ölçülen sayı) yaz.

- [ ] **Step 6: Diğer üç dosyayı güncelle**

1. `gelistirmeler/10-...md` — "Parçalar" bölümüne yeni madde:

```markdown
### 7) `generate-war-excludes.sh` — üretilen WAR dışlama listesi

Descriptor gibi, WAR'ın dışlama listesi de **üretilir**. Kaynağı `zeus-wildfly-module`
(SOAP için ek olarak `zeus-soap-wildfly-module`) runtime kapanışıdır; çıktısı
`zeus-parent` ve `zeus-soap-parent` POM'larındaki marker bloklarıdır.
`install-zeus-module.sh` kendi sonunda bunu çağırır — module ve liste aynı kapanıştan
üretildiği için ayrışamazlar. CI için: `--check`.
Gerekçe: `19-war-paketleme-module-farkindaligi.md`.
```

2. `gelistirmeler/17-module-yenileme-runbook.md` — sınıf yükleme adımının yanına:

```markdown
> `install-zeus-module.sh` çalıştıktan sonra `zeus-parent`/`zeus-soap-parent` POM'larındaki
> üretilmiş WAR dışlama listeleri de değişmiş olabilir — **commit edilmeleri gerekir**.
> Kontrol: `./scripts/generate-war-excludes.sh --check`.
```

3. `CLAUDE.md` — "Paylaşımlı WildFly module" bölümüne:

```markdown
./scripts/generate-war-excludes.sh --check    # WAR dışlama listeleri module ile uyumlu mu
# install-zeus-module.sh bunu kendi sonunda --write ile zaten çağırır.
```

- [ ] **Step 7: Commit**

```bash
git add gelistirmeler/ CLAUDE.md docs/superpowers/plans/
git commit -m "WAR paketleme module-farkındalığı: dokümantasyon ve doğrulama sonuçları"
```
