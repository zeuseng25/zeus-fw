#!/usr/bin/env bash
# Kurulu com.zeus module'ündeki her 3. parti autoconfig sınıfı SINIFLANDIRILMIŞ mı?
#
# NEDEN: com.zeus tüm uygulamaların birleşimidir. Sınıflandırılmamış bir autoconfig,
# onu istemeyen HER uygulamada çalışır (bugünkü spring-ai ağrısının kaynağı). Çalışma
# zamanı fail-open olduğu için (filtre tanımadığını veto etmez) yakalama yeri BURASIDIR.
#
# Sınıflandırma listesi ZeusCapabilities.java'dan OKUNUR, buraya kopyalanmaz —
# test-com-zeus-jakarta-api-kapsama.sh ile aynı "türet, sabitleme" disiplini.
#
# Kullanım:
#   ./scripts/test-autoconfig-sahipligi.sh
#   WILDFLY_HOME=/path/staging SLOT=1.1.0 ./scripts/test-autoconfig-sahipligi.sh
set -uo pipefail

FW_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WILDFLY_HOME="${WILDFLY_HOME:-/Users/omer/workspaces/intellij/wildfly-41/wildfly-41.0.0.Final}"
SLOT="${SLOT:-main}"
MODULE_DIR="${WILDFLY_HOME}/modules/com/zeus/${SLOT}"
KAYIT="${FW_ROOT}/zeus-base/src/main/java/com/zeus/framework/autoconfig/ZeusCapabilities.java"
fail=0

echo "── test-autoconfig-sahipligi.sh"

[[ -f "${KAYIT}" ]] || { echo "  ❌ yetenek kaydı bulunamadı: ${KAYIT}"; exit 1; }
[[ -d "${MODULE_DIR}" ]] || { echo "  ❌ module kurulu değil: ${MODULE_DIR}"; echo "     Önce: ./scripts/install-zeus-module.sh"; exit 1; }

# 1) Sınıflandırma önekleri: YALNIZ HEPSI ve HER_ZAMAN_SERBEST yapılarının İÇİNDEN.
# NOT (fix round 1, Important 1): önceki sürüm KAYIT dosyasının TÜMÜNÜ grep'liyordu —
# dosyaya eklenecek sıradan bir yorum ("... örnek: bir gün "org.acme.leaky." burada
# yanlışlıkla eklenirse ne olur?" gibi) küçük harfle başlayıp noktayla biten herhangi bir
# tırnaklı dize içeriyorsa, SINIFLANDIRMA LİSTESİNE AİT OLMADIĞI HALDE bir önek gibi
# sayılıyordu — guard'ın önlemeye çalıştığı "sessiz genişleme" riskinin ta kendisi.
# test-com-zeus-jakarta-api-kapsama.sh'daki disiplinle aynı: çıkarım, dosyanın TAMAMINDAN
# değil, sınıflandırmayı fiilen TANIMLAYAN bloktan yapılır. Blok, HEPSI bildiriminin
# başladığı satırdan sahipBul() metodunun başladığı satıra kadar (HER_ZAMAN_SERBEST'i de
# kapsar) — bu iki metin sınıflandırmanın TEK gerçek kaynağıdır; öncesindeki/sonrasındaki
# Javadoc, yorum ya da başka kod ÖLÇÜME KARIŞMAZ.
BLOK="$(sed -n '/public static final List<ZeusCapability> HEPSI = List\.of(/,/public static Optional<ZeusCapability> sahipBul(/p' "${KAYIT}")"
if [[ -z "${BLOK}" ]]; then
    echo "  ❌ ölçüm yapılamadı: ZeusCapabilities.java'da HEPSI..sahipBul() bloğu bulunamadı (dosya yapısı değişmiş olabilir)"
    exit 1
fi
# NOT: BSD/macOS grep (-E) boş alternatif "(x|)" içeren regex'i reddediyor ("empty
# (sub)expression"); bu yüzden brief'teki "(autoconfigure\.|)" yerine eşdeğer
# "(autoconfigure\.)?" kullanılıyor — anlam aynı, taşınabilirlik için değişti.
ONEKLER="$(grep -oE '"[a-z][a-zA-Z0-9_.]*\.(autoconfigure\.)?"' <<< "${BLOK}" | tr -d '"' | sort -u)"
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
#
# NOT (fix round 1, Minor 3): unzip çıkışı "aranan girdi bu jarda yok" (exit 11 — normal;
# çoğu jarın .imports dosyası zaten yoktur) durumu ile "bu jar HİÇ OKUNAMADI" (bozuk/erişilemez
# arşiv — exit 9, 2, vb.) durumu 2>/dev/null ile AYNI ŞEKİLDE yutulmasın diye ayrılır: exit kodu
# 0 ya da 11 ise normal (girdi bulundu YA DA jarda .imports hiç yok), başka her kod (2/3/9/50/...)
# jarın kendisi OKUNAMADI demektir — ÖLÇÜM HATASI. İkincisi olmadan, içinde gerçek autoconfig
# sınıfları olan bozuk bir jar, hiç uyarı vermeden hiçbir şey katkılamazdı — bu guardın
# "sessiz yeşil yasak" ilkesinin ihlali olurdu. Repo bunun bir benzerini daha önce jandex embed
# döngüsünde yaşadı (uyarıp devam etti, sonucu deploy sırasında OutOfMemoryError oldu) — burada
# aynı hataya düşülmez.
#
# NOT: bu açıklama BİLEREK $(...) komut ikamesinin DIŞINDA duruyor — macOS'un bash 3.2'sinde
# $(...) içindeki bir yorum satırında geçen TEK bir kesme işareti (') parser'ı bozuyor (bilinen
# bash 3.2 kusuru); bu yüzden aşağıdaki döngü içinde kesme işaretli yorum YOKTUR.
OKUNAMAYAN="$(mktemp)"
trap 'rm -f "${OKUNAMAYAN}"' EXIT
SINIFLAR="$(
    for j in "${MODULE_DIR}"/*.jar; do
        cikti="$(unzip -p "${j}" "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports" 2>/dev/null)"
        kod=$?
        # exit 0/11 normal (yukarida acikliyor); baska kod = jar okunamadi.
        if [[ ${kod} -ne 0 && ${kod} -ne 11 ]]; then
            echo "${j}" >> "${OKUNAMAYAN}"
            continue
        fi
        printf '%s\n' "${cikti}"
        echo
    done | grep -vE '^\s*(#|$)' | sort -u
)"
if [[ -s "${OKUNAMAYAN}" ]]; then
    echo "  ❌ OKUNAMAYAN jar(lar) — ÖLÇÜM HATASI (içeriği bilinmiyor, sessizce hiçbir şey katkılamadı):"
    sed 's/^/       /' "${OKUNAMAYAN}"
    echo "     Bozuk/erişilemez bir jar, gerçek autoconfig sınıfları taşıyor olabilir; guard bunu"
    echo "     'sessizce katkısız' saymak yerine KIRMIZI döner."
    exit 1
fi
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
