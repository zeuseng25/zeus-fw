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
