#!/usr/bin/env bash
# WAR paketleme davranışının uçtan uca testi (gerçek build + gerçek WAR).
set -uo pipefail
FW_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP="${FW_ROOT}/../spring-wildfly-arch"
fail=0
say() { if [[ "$1" == 0 ]]; then echo "  ✅ $2"; else echo "  ❌ $2"; fail=1; fi }

libs() { unzip -l "$(ls -t "${APP}"/target/*.war | head -1)" \
         | awk '{print $4}' | grep '^WEB-INF/lib/.*\.jar$' | sed 's#WEB-INF/lib/##' | sort; }

# spring-wildfly-arch/pom.xml <relativePath/> ile zeus-parent'ı ~/.m2'den çözer; framework'ü
# ÇALIŞMA AĞACINDAN install ETMEDEN bu test bayat bir kurulumu ölçer ve yeşil görünebilir
# (fix round 1, Important 3). Bu yüzden testin ilk adımı HER ZAMAN framework'ü kurmak.
echo ">> 0) zeus-fw çalışma ağacı kuruluyor (mvn install)"
( cd "${FW_ROOT}" && mvn -q install -DskipTests ) || { echo "❌ zeus-fw install"; exit 1; }

echo ">> A) REGRESYON: mevcut app'in WAR içeriği DEĞİŞMEMELİ"
( cd "${APP}" && mvn -q clean package -DskipTests ) || { echo "❌ build"; exit 1; }
got="$(libs)"
# app zeus-sms'e (CXF SOAP istemcisi) bağımlıdır — hiçbir opt-in property YAZMAZ artık:
# CXF paylaşımlı com.zeus module'ünde (zeus-wildfly-module/pom.xml), opt-in mekanizması
# (zeus.descriptor.extra.modules / zeus.war.packaging-excludes.with-soap) tamamen kalktı.
# Beklenen zeus jar sayısı 8 (zeus-sms + zeus-ai-mcp + zeus-ai-agent dahil); CXF ve MCP
# yığınları module'den geldiği için WAR'a hiç GİRMEMELİ (standart dışlama listesi cxf-*
# ve mcp-* içeriyor). zeus-ai-agent de aynı kuralla WAR'a girer: module'e hiç girmediği
# için dışlama listesinde de yoktur (zeus-* jar'ları her zaman WAR'dadır).
expected="zeus-ai
zeus-ai-agent
zeus-ai-mcp
zeus-base
zeus-database
zeus-logger
zeus-service
zeus-sms"
got_names="$(sed 's/-2\.0\.0-SNAPSHOT\.jar$//' <<< "${got}" | sort)"
[[ "${got_names}" == "${expected}" ]] && say 0 "tam 8 zeus jar'ı (zeus-sms + zeus-ai-mcp + zeus-ai-agent dahil)" || { say 1 "tam 8 zeus jar'ı (zeus-sms + zeus-ai-mcp + zeus-ai-agent dahil)"; echo "--- gelen:"; echo "${got}"; }
grep -qE '^ojdbc' <<< "${got}" && say 1 "ojdbc WAR'da YOK" || say 0 "ojdbc WAR'da YOK"
grep -qE '^jakarta\.' <<< "${got}" && say 1 "jakarta api WAR'da YOK" || say 0 "jakarta api WAR'da YOK"
grep -qE '^cxf-' <<< "${got}" && say 1 "CXF (com.zeus) WAR'da YOK" || say 0 "CXF (com.zeus) WAR'da YOK"
# MCP yığını da module'den gelir: SDK jar'ları (mcp, mcp-core, mcp-json-jackson3,
# mcp-spring-webmvc) ve spring-ai-*mcp* WAR'a girmemeli. WAR'da taşınan tek MCP parçası
# zeus-ai-mcp'nin KENDİ jar'ıdır (zeus-* kuralı) ve o yukarıdaki listede sayılıyor.
grep -qE '^(mcp|mcp-core|mcp-json-jackson3|mcp-spring-webmvc)-' <<< "${got}" \
    && say 1 "MCP SDK (com.zeus) WAR'da YOK" || say 0 "MCP SDK (com.zeus) WAR'da YOK"
grep -qE '^spring-ai-' <<< "${got}" && say 1 "spring-ai-* (com.zeus) WAR'da YOK" \
    || say 0 "spring-ai-* (com.zeus) WAR'da YOK"

echo ">> B) YENİ DAVRANIŞ: module'de olmayan bağımlılık WAR'a GİRMELİ"
# NOT: sabit paylaşımlı `/tmp/wpt-pom.bak` KULLANMIYORUZ — herkesin yazabildiği ortak bir
# yolda tek yedek, B'nin build'i sırasında Ctrl-C/abort olursa kardeş repo'nun pom.xml'ini
# enjekte edilmiş bağımlılıkla bırakırdı (fix round 1, Important 4). `mktemp` ile BENZERSİZ
# bir yedek dosyası ve EXIT trap'i ile HER ÇIKIŞ YOLUNDA (başarı/hata/SIGINT) geri yükleme.
app_pom_bak="$(mktemp "${TMPDIR:-/tmp}/wpt-pom.XXXXXX")"
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
# pom.xml'i şimdi geri yükle (rebuild'in restore edilmiş POM'a karşı olması için); EXIT
# trap'i idempotent olduğundan script sonunda tekrar tetiklenmesi zararsız (no-op).
restore_app_pom
( cd "${APP}" && mvn -q clean package -DskipTests >/dev/null 2>&1 )

exit ${fail}
