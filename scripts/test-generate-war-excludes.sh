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

echo ">> soap listesi"
SOAP="$(${GEN} --print soap)" || { echo "❌ script hata verdi"; exit 1; }
check "cxf-core VAR (com.zeus.soap'ta)"        0 'cxf-core'                      "${SOAP}"
check "spring-core VAR (birleşim)"             0 '[(|]spring-core[|)]'           "${SOAP}"
check "zeus-soap YOK (WAR'da taşınır)"         1 'zeus-soap'                     "${SOAP}"

echo ">> soap listesi standard'ın üst kümesi olmalı"
std_n=$(tr '|' '\n' <<< "${STD}"  | wc -l | tr -d ' ')
soap_n=$(tr '|' '\n' <<< "${SOAP}" | wc -l | tr -d ' ')
if (( soap_n > std_n )); then echo "  ✅ soap(${soap_n}) > standard(${std_n})"; else echo "  ❌ soap(${soap_n}) > standard(${std_n}) değil"; fail=1; fi

exit ${fail}
