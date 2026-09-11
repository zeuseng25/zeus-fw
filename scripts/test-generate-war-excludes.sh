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
check "spring-core VAR (module'de)"            0 '[(|]spring-core[|)]'           "${STD}"
check "hibernate-core VAR (module'de)"         0 '[(|]hibernate-core[|)]'        "${STD}"
check "zeus-base YOK (WAR'da taşınır)"         1 'zeus-base'                     "${STD}"
check "commons-io YOK (module'de değil)"       1 'commons-io'                    "${STD}"
check "ojdbc sabit kuyruğu VAR"                0 'ojdbc\[0-9\]\+'                "${STD}"
check "jakarta api sabit kuyruğu VAR"          0 'jakarta'                       "${STD}"
check "sürüm koruması -[0-9] VAR"              0 '\)-\[0-9\]'                    "${STD}"
check "%regex sarmalayıcı VAR"                 0 '^%regex\[WEB-INF/lib/'         "${STD}"
check "cxf-core VAR (artık com.zeus'ta)"       0 'cxf-core'                      "${STD}"

echo ">> soap listesi"
SOAP="$(${GEN} --print soap)" || { echo "❌ script hata verdi"; exit 1; }
check "cxf-core VAR (com.zeus ∪ com.zeus.soap)" 0 'cxf-core'                      "${SOAP}"
check "spring-core VAR (birleşim)"             0 '[(|]spring-core[|)]'           "${SOAP}"
check "zeus-soap YOK (WAR'da taşınır)"         1 'zeus-soap'                     "${SOAP}"

echo ">> soap listesi standard'ın üst kümesi olmalı (birleşim: com.zeus ∪ com.zeus.soap)"
# CXF artık com.zeus'ta OLDUĞU İÇİN (zeus-wildfly-module'ün cxf-spring-boot-starter-jaxws
# bağımlılığı) zeus-soap-wildfly-module'ün TEK ayırt edici bağımlılığı da standart kapanışta
# zaten var — birleşim standart listeyle AYNI KÜMEYE denk gelebilir (bu koşuda öyle: com.zeus.soap
# küme farkıyla boşalıyor, gelistirmeler/20-zeus-sms.md). Bu yüzden iddia KATI ">" DEĞİL,
# küme içerme ("soap, standard'ın hiçbir alternatifini KAYBETMEZ") — eşitlik de GEÇERLİ.
std_n=$(tr '|' '\n' <<< "${STD}"  | wc -l | tr -d ' ')
soap_n=$(tr '|' '\n' <<< "${SOAP}" | wc -l | tr -d ' ')
std_sorted="$(tr '|' '\n' <<< "${STD}"  | sort -u)"
soap_sorted="$(tr '|' '\n' <<< "${SOAP}" | sort -u)"
missing="$(comm -23 <(echo "${std_sorted}") <(echo "${soap_sorted}") || true)"
if (( soap_n >= std_n )) && [[ -z "${missing//[[:space:]]/}" ]]; then
    echo "  ✅ soap(${soap_n}) ⊇ standard(${std_n})"
else
    echo "  ❌ soap(${soap_n}) ⊇ standard(${std_n}) değil"; fail=1
    [[ -n "${missing//[[:space:]]/}" ]] && { echo "--- standard'da olup soap'ta olmayan:"; echo "${missing}"; }
fi

exit ${fail}
