#!/usr/bin/env bash
# com.zeus module sözleşmesine CXF SIZMAMALI.
# zeus-sms CXF'e bağımlıdır ama com.zeus.soap'tan beslenir; zeus-wildfly-module'e
# eklenirse kapanışı com.zeus'a taşınır ve "com.zeus büyümez" kararı bozulur.
# Gerekçe: gelistirmeler/20-zeus-sms.md
set -uo pipefail
FW_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
fail=0

if grep -q "zeus-sms" "${FW_ROOT}/zeus-wildfly-module/pom.xml"; then
    echo "  ❌ zeus-sms, zeus-wildfly-module'e eklenmiş — CXF com.zeus'a sızar"; fail=1
else
    echo "  ✅ zeus-sms zeus-wildfly-module'de değil"
fi

out="$(mktemp)"
( cd "${FW_ROOT}/zeus-wildfly-module" && mvn -q -B dependency:list \
    -DincludeScope=runtime -DoutputFile="${out}" >/dev/null 2>&1 )
if grep -qE ':cxf-' "${out}"; then
    echo "  ❌ com.zeus kapanışında CXF artefaktı var:"; grep -E ':cxf-' "${out}" | head -5; fail=1
else
    echo "  ✅ com.zeus kapanışında CXF yok"
fi
rm -f "${out}"
exit ${fail}
