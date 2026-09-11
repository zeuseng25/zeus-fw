#!/usr/bin/env bash
# Kurulu com.zeus module'ündeki her 3. parti autoconfig sınıfı SINIFLANDIRILMIŞ mı?
#
# NEDEN: com.zeus tüm uygulamaların birleşimidir. Sınıflandırılmamış bir autoconfig,
# onu istemeyen HER uygulamada çalışır (bugünkü spring-ai ağrısının kaynağı). Çalışma
# zamanı fail-open olduğu için (filtre tanımadığını veto etmez) yakalama yeri BURASIDIR.
#
# Sınıflandırma listesi ZeusCapabilities.java'dan OKUNUR, buraya kopyalanmaz —
# test-com-zeus-jakarta-api-kapsama.sh ile aynı "türet, sabitleme" disiplini.
set -uo pipefail

FW_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WILDFLY_HOME="${WILDFLY_HOME:-/Users/omer/workspaces/intellij/wildfly-41/wildfly-41.0.0.Final}"
MODULE_DIR="${WILDFLY_HOME}/modules/com/zeus/main"
KAYIT="${FW_ROOT}/zeus-base/src/main/java/com/zeus/framework/autoconfig/ZeusCapabilities.java"
fail=0

echo "── test-autoconfig-sahipligi.sh"

[[ -f "${KAYIT}" ]] || { echo "  ❌ yetenek kaydı bulunamadı: ${KAYIT}"; exit 1; }
[[ -d "${MODULE_DIR}" ]] || { echo "  ❌ module kurulu değil: ${MODULE_DIR}"; echo "     Önce: ./scripts/install-zeus-module.sh"; exit 1; }

# 1) Sınıflandırma önekleri: kaynak dosyadaki "..." dizelerinden, paket adı görünenler.
# NOT: BSD/macOS grep (-E) boş alternatif "(x|)" içeren regex'i reddediyor ("empty
# (sub)expression"); bu yüzden brief'teki "(autoconfigure\.|)" yerine eşdeğer
# "(autoconfigure\.)?" kullanılıyor — anlam aynı, taşınabilirlik için değişti.
ONEKLER="$(grep -oE '"[a-z][a-zA-Z0-9_.]*\.(autoconfigure\.)?"' "${KAYIT}" | tr -d '"' | sort -u)"
if [[ -z "${ONEKLER}" ]]; then
    echo "  ❌ ZeusCapabilities.java'dan hiç önek çıkarılamadı — ÖLÇÜM HATASI (sessiz yeşil yasak)."
    exit 1
fi

# 2) Module'deki tüm autoconfig sınıfları.
# NOT: bazı .imports dosyaları sonda satır sonu (\n) TAŞIMIYOR (ör. springdoc-openapi-
# starter-webmvc-api). "echo" olmadan ardışık unzip çıktıları birleşir ve son sınıf adı
# bir SONRAKİ jar'ın ilk sınıf adıyla TEK satıra kaynaşır — bu hem yanlış sayım hem
# (daha kötüsü) YANLIŞ EŞLEŞME riski taşır (kaynaşmış satır, aslında sahipsiz olan bir
# sınıfın önüne/ardına sahipli bir önekle başlayan/biten başka bir sınıf adı eklenirse
# yanlışlıkla "bulundu" sayılabilir). Her jar çıktısından sonra açık bir "echo" ile
# ayraç satırı eklenir.
SINIFLAR="$(for j in "${MODULE_DIR}"/*.jar; do
    unzip -p "${j}" "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports" 2>/dev/null
    echo
done | grep -vE '^\s*(#|$)' | sort -u)"
if [[ -z "${SINIFLAR}" ]]; then
    echo "  ❌ module'de hiç autoconfig sınıfı bulunamadı — ÖLÇÜM HATASI."
    exit 1
fi
echo "  >> ${MODULE_DIR##*/modules/}: $(wc -l <<< "${SINIFLAR}" | tr -d ' ') autoconfig sınıfı, $(wc -l <<< "${ONEKLER}" | tr -d ' ') önek"

# 3) Her sınıf bir öneke düşmeli.
SAHIPSIZ=""
while IFS= read -r sinif; do
    [[ -z "${sinif}" ]] && continue
    bulundu=0
    while IFS= read -r onek; do
        [[ "${sinif}" == "${onek}"* ]] && { bulundu=1; break; }
    done <<< "${ONEKLER}"
    (( bulundu )) || SAHIPSIZ+="${sinif}"$'\n'
done <<< "${SINIFLAR}"

if [[ -n "${SAHIPSIZ//[$'\n' ]/}" ]]; then
    echo "  ❌ SINIFLANDIRILMAMIŞ autoconfig (bu sınıflar HER uygulamada çalışır):"
    sed '/^$/d' <<< "${SAHIPSIZ}" | head -20 | sed 's/^/       /'
    adet=$(sed '/^$/d' <<< "${SAHIPSIZ}" | wc -l | tr -d ' ')
    (( adet > 20 )) && echo "       … ve $(( adet - 20 )) tane daha"
    echo "     Çözüm: ${KAYIT##*/} içinde ya bir yeteneğe ya da HER_ZAMAN_SERBEST'e ekleyin."
    fail=1
else
    echo "  ✅ module'deki her autoconfig sınıfı sınıflandırılmış"
fi

exit "${fail}"
