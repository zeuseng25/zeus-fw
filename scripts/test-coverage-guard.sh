#!/usr/bin/env bash
# Module'de OLMAYAN bir bağımlılık artık deploy'u durdurmamalı.
set -uo pipefail
FW_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP="${FW_ROOT}/../spring-wildfly-arch"

# spring-wildfly-arch/pom.xml <relativePath/> ile zeus-parent'ı ~/.m2'den çözer; framework'ü
# ÇALIŞMA AĞACINDAN install ETMEDEN bu test bayat bir kurulumu ölçer ve yeşil görünebilir.
# Bu yüzden testin ilk adımı HER ZAMAN framework'ü kurmak (bkz. test-war-packaging.sh).
echo ">> 0) zeus-fw çalışma ağacı kuruluyor (mvn install)"
( cd "${FW_ROOT}" && mvn -q install -DskipTests ) || { echo "❌ zeus-fw install"; exit 1; }

# Sabit paylaşımlı bir /tmp yolu YERİNE mktemp ile BENZERSİZ bir yedek dosyası kullanılır;
# yedek trap KURULMADAN ÖNCE yazılır (yedek boşken bir kesintide restore, kardeş repo'nun
# pom.xml'ini SIFIRLAR — bu hata bir kez yapıldı, bkz. test-war-packaging.sh).
app_pom_bak="$(mktemp "${TMPDIR:-/tmp}/cg-pom.XXXXXX")"
cp "${APP}/pom.xml" "${app_pom_bak}"
restore_app_pom() {
    if [[ -s "${app_pom_bak}" ]]; then
        cp "${app_pom_bak}" "${APP}/pom.xml"
        rm -f "${app_pom_bak}"
    fi
}
trap restore_app_pom EXIT

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
restore_app_pom

if [[ "${rc}" == 0 ]]; then echo "✅ module'de olmayan bağımlılık deploy'u durdurmuyor"; exit 0; fi
echo "❌ script hâlâ exit ${rc} veriyor (yanlış pozitif)"; exit 1
