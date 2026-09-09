#!/usr/bin/env bash
# zeus.soap.module.slot HER tipte çözülmeli (standart tip dahil) — SOAP module'ünü
# opt-in import eden standart uygulamalar slot'u bu property'den alır.
set -uo pipefail
FW_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
fail=0
val() {  # $1 = proje dizini
    ( cd "$1" && mvn -q -B -Dstyle.color=never help:evaluate \
        -Dexpression=zeus.soap.module.slot -DforceStdout 2>/dev/null )
}
check() { # $1=aciklama $2=deger
    if [[ "$2" == "main" ]]; then echo "  ✅ $1 → $2"; else echo "  ❌ $1 → '$2' (beklenen: main)"; fail=1; fi
}
check "standart tip (spring-wildfly-arch)" "$(val "${FW_ROOT}/../spring-wildfly-arch")"
check "SOAP tipi  (zeus-sample-soap)"      "$(val "${FW_ROOT}/../zeus-sample-soap")"
check "zeus-parent'ın kendisi"             "$(val "${FW_ROOT}/zeus-parent")"
exit ${fail}
