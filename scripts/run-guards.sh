#!/usr/bin/env bash
#
# GUARD SÜİTİ TOPLAYICISI (PLATFORM scripti).
#
# Neden var: repoda dokuz guard script'i vardı ama onları TOPLU çalıştıran hiçbir şey yoktu —
# ne bir çalıştırıcı, ne Maven binding'i, ne CI bağlaması. Bir guard'ın değeri çalıştırılma
# sıklığı kadardır; kimsenin koşturmadığı bir süit, "drift yapısal olarak imkânsız"
# iddiasını taşıyamaz (final review, Important 5).
#
# Bu script yalnızca TOPLAYICIDIR: guard'ların kendisini değiştirmez, sırayla koşturur,
# İLK KIRMIZIDA durur ve non-zero döner.
#
# ÖNEMLİ — kardeş repo bağımlılığı: guard'ların bir kısmı bu repoyla değil, onu TÜKETEN
# uygulamalarla ölçüm yapar (`../spring-wildfly-arch`, `../zeus-sample-soap`) ve bir kısmı
# ayrıca kurulu bir WildFly ister. `zeus-fw`'nin TAZE BİR KLONUNDA bunlar koşamaz. O yüzden
# eksik ön koşul VARSAYILAN OLARAK "atlandı" diye AÇIKÇA raporlanır, sessizce yeşile
# sayılmaz; tam ortamda ise `--require-all` ile atlama HATA'ya dönüştürülebilir.
#
# Kullanım:
#   ./scripts/run-guards.sh                 # hepsi; ön koşulu olmayanlar atlanır
#   ./scripts/run-guards.sh --fw-only       # yalnız zeus-fw'ye kendi başına yeten guard'lar
#   ./scripts/run-guards.sh --require-all   # atlama = HATA (tam ortam / CI için)
#   ./scripts/run-guards.sh --list          # ne koşacağını göster, hiçbir şey çalıştırma
#
set -uo pipefail

FW_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP_STD="${FW_ROOT}/../spring-wildfly-arch"
APP_SOAP="${FW_ROOT}/../zeus-sample-soap"
WILDFLY_HOME="${WILDFLY_HOME:-/Users/omer/workspaces/intellij/wildfly-41/wildfly-41.0.0.Final}"

MODE="all"
REQUIRE_ALL=0
for arg in "$@"; do
    case "${arg}" in
        --fw-only)     MODE="fw-only" ;;
        --require-all) REQUIRE_ALL=1 ;;
        --list)        MODE="list" ;;
        -h|--help)
            sed -n '2,30p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
            exit 0 ;;
        *) echo "bilinmeyen argüman: ${arg}" >&2; exit 2 ;;
    esac
done

# Süit tablosu: "gereksinim|komut|açıklama"
#   gereksinim = fw            → yalnız bu repo yeter
#                app-std       → ../spring-wildfly-arch gerekir
#                app-soap      → ../zeus-sample-soap gerekir
#                app-both      → ikisi de gerekir
#                app-std+wf    → ../spring-wildfly-arch VE kurulu WildFly gerekir
#
# SIRA KASITLIDIR: önce ucuz ve kendi başına yeten guard'lar (hızlı kırmızı), sonra
# kardeş repo build'i gerektiren pahalı olanlar.
SUITE=(
  "fw|${FW_ROOT}/scripts/test-generate-war-excludes.sh|Üretilen dışlama listelerinin içeriği doğru mu"
  "fw|${FW_ROOT}/scripts/test-no-war-keep.sh|Kaldırılan WAR-keep property'sinin kod kalıntısı var mı"
  "fw|${FW_ROOT}/scripts/test-com-zeus-cxf-sizintisi.sh|com.zeus sözleşmesinde CXF var mı, zeus-* jar'ı sızmış mı"
  "fw|${FW_ROOT}/scripts/test-generator-wiring.sh|Üreteç doğru çağrılıyor + listeler güncel mi"
  "app-both|${FW_ROOT}/scripts/test-soap-slot-property.sh|zeus.soap.module.slot her tipte çözülüyor mu"
  "app-std|${FW_ROOT}/scripts/test-war-packaging.sh|Standart tip WAR içeriği (uçtan uca build)"
  "app-soap|${FW_ROOT}/scripts/test-war-packaging-soap.sh|SOAP tipi WAR içeriği (uçtan uca build)"
  "app-std+wf|${FW_ROOT}/scripts/test-coverage-guard.sh|Module'de olmayan bağımlılık deploy'u durdurmuyor"
  "app-std+wf|${FW_ROOT}/scripts/verify-module-coverage.sh ${APP_STD}|Deploy ön-kontrolü (gerçek app + gerçek sunucu)"
)

# Ön koşul karşılanıyor mu? Karşılanmıyorsa NEDENİNİ stdout'a basar ve 1 döner.
precondition_met() {  # $1 = gereksinim etiketi
    case "$1" in
        fw) return 0 ;;
        app-std)
            [[ -d "${APP_STD}" ]] && return 0
            echo "kardeş repo yok: ${APP_STD}"; return 1 ;;
        app-soap)
            [[ -d "${APP_SOAP}" ]] && return 0
            echo "kardeş repo yok: ${APP_SOAP}"; return 1 ;;
        app-both)
            [[ -d "${APP_STD}" && -d "${APP_SOAP}" ]] && return 0
            echo "kardeş repo(lar) yok: ${APP_STD} ve/veya ${APP_SOAP}"; return 1 ;;
        app-std+wf)
            if [[ ! -d "${APP_STD}" ]]; then echo "kardeş repo yok: ${APP_STD}"; return 1; fi
            [[ -d "${WILDFLY_HOME}/modules" ]] && return 0
            echo "kurulu WildFly yok: ${WILDFLY_HOME} (WILDFLY_HOME ile gösterilebilir)"; return 1 ;;
        *) echo "bilinmeyen gereksinim etiketi: $1"; return 1 ;;
    esac
}

total="${#SUITE[@]}"; ran=0; skipped=0
echo "══════════════════════════════════════════════════════════════════════"
echo " Zeus guard süiti — ${#SUITE[@]} guard"
echo " FW_ROOT      : ${FW_ROOT}"
echo " WILDFLY_HOME : ${WILDFLY_HOME}"
echo "══════════════════════════════════════════════════════════════════════"

for row in "${SUITE[@]}"; do
    need="${row%%|*}"; rest="${row#*|}"
    cmd="${rest%%|*}"; desc="${rest#*|}"
    name="$(basename "${cmd%% *}")"

    if [[ "${MODE}" == "list" ]]; then
        printf '%-34s  [%s]  %s\n' "${name}" "${need}" "${desc}"
        continue
    fi

    if [[ "${MODE}" == "fw-only" && "${need}" != "fw" ]]; then
        echo "── ATLANDI  ${name}  (--fw-only: '${need}' gerektiriyor)"
        skipped=$((skipped + 1))
        continue
    fi

    if ! reason="$(precondition_met "${need}")"; then
        echo "── ATLANDI  ${name}  (${reason})"
        skipped=$((skipped + 1))
        if (( REQUIRE_ALL )); then
            echo
            echo "❌ --require-all: ön koşulu karşılanmayan guard ATLANAMAZ — ${name}" >&2
            exit 1
        fi
        continue
    fi

    echo
    echo "── ÇALIŞIYOR ${name} — ${desc}"
    # Guard'lar KENDİ çıktılarını basar; toplayıcı onu yutmaz (guard'ın değeri okunmasında).
    # `set -e` KULLANILMAZ: çıkış kodunu burada AÇIKÇA yakalayıp ilk kırmızıda duruyoruz.
    # Çıkış kodu AÇIKÇA yakalanır. `if ! ${cmd}; then rc=$?` YAZILMAZ: `!` operatörü $?'ı
    # tersine çevirir ve rc her zaman 0 okunurdu — kırmızı guard'ın kodu kaybolurdu.
    ${cmd}
    rc=$?
    if (( rc != 0 )); then
        echo
        echo "══════════════════════════════════════════════════════════════════════" >&2
        echo "❌ KIRMIZI: ${name} (çıkış kodu ${rc}) — süit burada DURDU." >&2
        echo "   Bu noktaya kadar koşan: ${ran}, atlanan: ${skipped} (süitte toplam ${#SUITE[@]})" >&2
        echo "══════════════════════════════════════════════════════════════════════" >&2
        exit "${rc}"
    fi
    ran=$((ran + 1))
done

if [[ "${MODE}" == "list" ]]; then
    exit 0
fi

echo
echo "══════════════════════════════════════════════════════════════════════"
echo "✅ Guard süiti YEŞİL — koşan: ${ran}/${total}, atlanan: ${skipped}"
if (( skipped > 0 )); then
    echo "   NOT: atlanan guard'lar hiçbir şey DOĞRULAMADI. Tam ortamda"
    echo "   './scripts/run-guards.sh --require-all' ile atlama HATA'ya döner."
fi
echo "══════════════════════════════════════════════════════════════════════"
