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

out="$(mktemp)"; err="$(mktemp)"
trap 'rm -f "${out}" "${err}"' EXIT

# mvn'in çıkış kodu MUTLAKA kontrol edilmeli: düşerse ${out} boş kalır ve grep
# hiçbir şey bulamaz — bu durumu "CXF yok" ile karıştırmak sahte yeşil üretir.
# stdout susturuluyor; stderr ayrı bir dosyaya yakalanıp YALNIZ hata dallarında
# basılıyor ki yeşil koşuda mvn/JVM gürültüsü (ör. sun.misc.Unsafe uyarıları)
# çıktıyı kirletmesin — guard'ın değeri okunmasında.
if ! ( cd "${FW_ROOT}/zeus-wildfly-module" && mvn -q -B dependency:list \
        -DincludeScope=runtime -DoutputFile="${out}" >/dev/null 2>"${err}" ); then
    echo "  ❌ ölçüm yapılamadı: mvn dependency:list başarısız — CXF sızıntısı bu koşuda doğrulanamadı"
    cat "${err}" >&2
    fail=1
elif [[ ! -s "${out}" ]]; then
    # mvn 0 dönebilir ama -q + -DoutputFile kombinasyonu bazen dosyayı hiç yazmaz.
    # zeus-wildfly-module'ün 150+ runtime bağımlılığı var; boş çıktı HER ZAMAN bir
    # ölçüm hatasıdır, meşru bir "bağımlılık yok" durumu değildir — sahte yeşile düşme.
    echo "  ❌ ölçüm yapılamadı: dependency:list çıktısı BOŞ (zeus-wildfly-module'ün 150+ bağımlılığı var; boş çıktı ölçüm hatasıdır)"
    cat "${err}" >&2
    fail=1
elif grep -qE ':cxf-' "${out}"; then
    echo "  ❌ com.zeus kapanışında CXF artefaktı var:"; grep -E ':cxf-' "${out}" | head -5; fail=1
else
    echo "  ✅ com.zeus kapanışında CXF yok"
fi
exit ${fail}
