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
