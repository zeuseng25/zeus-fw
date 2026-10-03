#!/usr/bin/env bash
# SOAP tipi WAR: com.zeus VE com.zeus.soap içeriğinin hiçbiri WAR'a girmemeli.
set -uo pipefail
FW_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP="${FW_ROOT}/../zeus-sample-soap"
fail=0
say() { if [[ "$1" == 0 ]]; then echo "  ✅ $2"; else echo "  ❌ $2"; fail=1; fi }

# zeus-sample-soap pom.xml <relativePath/> ile zeus-soap-parent'ı ~/.m2'den çözer; framework'ü
# ÇALIŞMA AĞACINDAN install ETMEDEN bu test bayat bir kurulumu ölçer ve yeşil görünebilir
# (bkz. test-war-packaging.sh'teki aynı düzeltme). Bu yüzden ilk adım HER ZAMAN framework'ü kurmak.
echo ">> 0) zeus-fw çalışma ağacı kuruluyor (mvn install)"
( cd "${FW_ROOT}" && mvn -q install -DskipTests ) || { echo "❌ zeus-fw install"; exit 1; }

( cd "${APP}" && mvn -q clean package -DskipTests ) || { echo "❌ build"; exit 1; }
libs="$(unzip -l "$(ls -t "${APP}"/target/*.war | head -1)" \
        | awk '{print $4}' | grep '^WEB-INF/lib/.*\.jar$' | sed 's#WEB-INF/lib/##' | sort)"
echo "${libs}"

grep -qE '^cxf-'      <<< "${libs}" && say 1 "CXF jar'ı WAR'da YOK"     || say 0 "CXF jar'ı WAR'da YOK"
grep -qE '^wsdl4j-'   <<< "${libs}" && say 1 "wsdl4j WAR'da YOK"        || say 0 "wsdl4j WAR'da YOK"
grep -qE '^spring-core-' <<< "${libs}" && say 1 "spring-core WAR'da YOK" || say 0 "spring-core WAR'da YOK"
grep -qE '^zeus-soap-' <<< "${libs}" && say 0 "zeus-soap WAR'da VAR"     || say 1 "zeus-soap WAR'da VAR"
grep -qE '^zeus-base-' <<< "${libs}" && say 0 "zeus-base WAR'da VAR"     || say 1 "zeus-base WAR'da VAR"

# GÖMÜLÜ TOMCAT — allowlist'ten denylist'e geçerken bu koruma kaybolmuştu ve WAR'a
# tomcat-embed-core (146 adet jakarta/servlet/** sınıfı → deployment classloader'ında
# servlet API'sinin ikinci kopyası → LinkageError) giriyordu. Yukarıdaki üç "yok"
# iddiası regresyonu YAKALAYAMADI çünkü hiçbiri "WAR'da SADECE zeus-* var" demiyordu.
grep -qE '^tomcat-embed-' <<< "${libs}" && say 1 "tomcat-embed-* WAR'da YOK"        || say 0 "tomcat-embed-* WAR'da YOK"
grep -qE '^spring-boot-tomcat-' <<< "${libs}" && say 1 "spring-boot-tomcat WAR'da YOK" || say 0 "spring-boot-tomcat WAR'da YOK"
grep -qE '^spring-boot-starter-tomcat' <<< "${libs}" && say 1 "spring-boot-starter-tomcat* WAR'da YOK" || say 0 "spring-boot-starter-tomcat* WAR'da YOK"

# KAPSAYICI İDDİA: ince SOAP WAR'ında zeus-* DIŞINDA hiçbir jar olmamalı. Tek tek
# "şu yok" kontrolleri yalnız bilinen regresyonları yakalar; bu iddia BİLİNMEYENİ de
# yakalar (final review, Critical 1).
non_zeus="$(grep -vE '^zeus-[a-z0-9-]+-[0-9]' <<< "${libs}" || true)"
if [[ -z "${non_zeus//[[:space:]]/}" ]]; then
    say 0 "WAR'da SADECE zeus-* jar'ları var"
else
    say 1 "WAR'da SADECE zeus-* jar'ları var"
    echo "--- zeus-* olmayan jar'lar:"; echo "${non_zeus}"
fi

exit ${fail}
