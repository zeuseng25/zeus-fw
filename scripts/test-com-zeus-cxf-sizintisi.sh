#!/usr/bin/env bash
# com.zeus module sözleşmesinin CXF ve zeus-* içeriği doğru mu.
#
# ESKİDEN bu guard "com.zeus'a CXF SIZMAMALI" derdi: CXF ayrı com.zeus.soap module'ündeydi,
# zeus-sms onu opt-in ile kullanırdı. Karar değişti — CXF artık BİLEREK paylaşımlı com.zeus
# module'ünde (zeus-wildfly-module/pom.xml, cxf-spring-boot-starter-jaxws); opt-in mekanizması
# tamamen kalktı. Bu yüzden iddia TERS ÇEVRİLDİ: com.zeus kapanışında CXF ARTIK OLMALI.
# Değişmeyen kısım: zeus-* jar'larının (zeus-sms dahil) KENDİSİ hâlâ zeus-wildfly-module'e
# EKLENMEZ — onlar WAR içinde taşınır, yalnız 3. parti kapanışları module'e girer.
# Gerekçe: gelistirmeler/20-zeus-sms.md (opt-in tarihi) + gelistirmeler/14 (tip parent'ları).
set -uo pipefail
FW_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
fail=0

if grep -q "zeus-sms" "${FW_ROOT}/zeus-wildfly-module/pom.xml"; then
    echo "  ❌ zeus-sms, zeus-wildfly-module'e eklenmiş — zeus-* jar'ı module'e sızar"; fail=1
else
    echo "  ✅ zeus-sms zeus-wildfly-module'de değil (zeus-* jar'ları WAR'da taşınır)"
fi

out="$(mktemp)"; err="$(mktemp)"
trap 'rm -f "${out}" "${err}"' EXIT

# mvn'in çıkış kodu MUTLAKA kontrol edilmeli: düşerse ${out} boş kalır ve grep
# hiçbir şey bulamaz — bu durumu "CXF var/yok" ile karıştırmak sahte sonuç üretir.
# stdout susturuluyor; stderr ayrı bir dosyaya yakalanıp YALNIZ hata dallarında
# basılıyor ki yeşil koşuda mvn/JVM gürültüsü (ör. sun.misc.Unsafe uyarıları)
# çıktıyı kirletmesin — guard'ın değeri okunmasında.
if ! ( cd "${FW_ROOT}/zeus-wildfly-module" && mvn -q -B dependency:list \
        -DincludeScope=runtime -DoutputFile="${out}" >/dev/null 2>"${err}" ); then
    echo "  ❌ ölçüm yapılamadı: mvn dependency:list başarısız — CXF varlığı bu koşuda doğrulanamadı"
    cat "${err}" >&2
    fail=1
elif [[ ! -s "${out}" ]]; then
    # mvn 0 dönebilir ama -q + -DoutputFile kombinasyonu bazen dosyayı hiç yazmaz.
    # zeus-wildfly-module'ün 150+ runtime bağımlılığı var; boş çıktı HER ZAMAN bir
    # ölçüm hatasıdır, meşru bir "bağımlılık yok" durumu değildir — sahte yeşile düşme.
    echo "  ❌ ölçüm yapılamadı: dependency:list çıktısı BOŞ (zeus-wildfly-module'ün 150+ bağımlılığı var; boş çıktı ölçüm hatasıdır)"
    cat "${err}" >&2
    fail=1
elif grep -qE ':cxf-core:' "${out}"; then
    echo "  ✅ com.zeus kapanışında CXF (cxf-core) VAR — CXF artık paylaşımlı"
else
    echo "  ❌ com.zeus kapanışında CXF (cxf-core) YOK — cxf-spring-boot-starter-jaxws eksik mi?"
    fail=1
fi
exit ${fail}
