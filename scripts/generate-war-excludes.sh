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
