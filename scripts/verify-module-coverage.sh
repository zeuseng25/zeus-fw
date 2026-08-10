#!/usr/bin/env bash
#
# Module kapsam doğrulaması (PLATFORM scripti).
#
# Bir uygulamanın runtime 3. parti bağımlılıklarının, paylaşımlı WildFly 'com.zeus'
# module'ü tarafından karşılanıp karşılanmadığını denetler. Module'de OLMAYAN ve WAR'a da
# (zeus.war.keep) bundle edilmeyen bir bağımlılık varsa, WildFly'da NoClassDefFoundError
# olmadan ÖNCE non-zero exit ile uyarır → deploy'dan önce yakalanır.
#
# Neden gerekli: zeus-dependencies BOM ~1000+ lib'in SÜRÜMÜNÜ yönetir (app sürümsüz
# ekleyebilir, derlenir, lokalde çalışır), ama yalnız zeus-wildfly-module'deki küçük
# altküme gerçekten module'dedir. Aradaki uçurum WildFly'da geç ve kriptik patlar.
#
# Kullanım:
#   ./scripts/verify-module-coverage.sh [app-dizini]   (varsayılan: cwd)
#   WILDFLY_HOME=/path/staging ./scripts/verify-module-coverage.sh /path/app
#
set -euo pipefail

WILDFLY_HOME="${WILDFLY_HOME:-/Users/omer/workspaces/intellij/wildfly-41/wildfly-41.0.0.Final}"
APP_DIR="${1:-$(pwd)}"

# install-zeus-module.sh ile AYNI dışlama kümesi (artifactId bazında):
# bunlar module'de DEĞİL → kapsam kontrolünde aranmaz.
#  - jakarta.*-api  : WildFly server module'lerinden gelir
#  - zeus-*         : uygulamanın WAR'ında taşınır
#  - lombok/jarmode : runtime'da gereksiz
EXCLUDE_REGEX='^(jakarta\.(activation|annotation|inject|persistence|transaction|validation|xml\.bind|xml\.ws|xml\.soap)-api|lombok|spring-boot-jarmode-[a-z]+|zeus-[a-z0-9-]+)$'

cd "${APP_DIR}"
MVN="mvn"

# App'in hedeflediği module SLOT'u (zeus.module.slot, zeus-parent'tan; üretilen
# jboss-deployment-structure.xml'e yazılan değerle aynı kaynak). Kapsam bu slot'a karşı denetlenir.
SLOT="$(${MVN} -q -Dstyle.color=never help:evaluate -Dexpression=zeus.module.slot -DforceStdout 2>/dev/null || true)"
[[ -z "${SLOT}" || "${SLOT}" == "null"* ]] && SLOT="main"
MODULE_DIR="${WILDFLY_HOME}/modules/com/zeus/${SLOT}"

# SOAP tipi uygulama mı? (zeus-soap-parent, zeus.soap.module.slot property'sini tanımlar)
# Öyleyse kapsam denetimi com.zeus ∪ com.zeus.soap birleşimine karşı yapılır.
SOAP_SLOT="$(${MVN} -q -Dstyle.color=never help:evaluate -Dexpression=zeus.soap.module.slot -DforceStdout 2>/dev/null || true)"
[[ "${SOAP_SLOT}" == "null"* ]] && SOAP_SLOT=""
SOAP_MODULE_DIR=""
[[ -n "${SOAP_SLOT}" ]] && SOAP_MODULE_DIR="${WILDFLY_HOME}/modules/com/zeus/soap/${SOAP_SLOT}"

# Üretilen-descriptor kontrolü: WAR build edilmişse içinde framework'ün ürettiği
# jboss-deployment-structure.xml olmalı. Yoksa zeus-generated-descriptor profili devreye
# girmemiştir (tipik neden: src/main/webapp dizini yok — boşsa .gitkeep ile var edilmeli);
# böyle bir WAR WildFly'da com.zeus'u göremez ve kriptik açılış hatası verir.
WAR="$(ls -t "${APP_DIR}"/target/*.war 2>/dev/null | head -n1 || true)"
if [[ -n "${WAR}" ]]; then
    if ! unzip -p "${WAR}" WEB-INF/jboss-deployment-structure.xml 2>/dev/null | grep -q 'name="com.zeus"'; then
        echo "HATA: WAR'da üretilmiş jboss-deployment-structure.xml yok: $(basename "${WAR}")" >&2
        echo "      zeus-generated-descriptor profili devreye girmemiş görünüyor." >&2
        echo "      Kontrol: app'te src/main/webapp dizini var mı? (boşsa .gitkeep ile oluşturun" >&2
        echo "      — profil bu dizinin varlığıyla aktifleşir) Sonra yeniden build edin." >&2
        exit 2
    fi
fi

# Slot-kurulu-mu kontrolü: app'in işaret ettiği slot sunucuda yoksa deploy kriptik açılış
# hatasıyla değil, burada net mesajla dursun.
if [[ ! -f "${MODULE_DIR}/module.xml" ]]; then
    echo "HATA: uygulamanın hedeflediği com.zeus:${SLOT} slot'u bu sunucuda kurulu değil: ${MODULE_DIR}" >&2
    if [[ "${SLOT}" == "main" ]]; then
        echo "      Önce: ( cd zeus-fw && ./scripts/install-zeus-module.sh )" >&2
    else
        echo "      Önce: ( cd zeus-fw && ./scripts/install-zeus-module.sh --slot ${SLOT} )" >&2
        echo "      (yeni slot kurulumu WildFly restart'ı gerektirmez)" >&2
    fi
    exit 2
fi

# App'in zeus.war.keep değeri (WAR'a bundle edilenler → module'de aranmaz)
KEEP="$(${MVN} -q -Dstyle.color=never help:evaluate -Dexpression=zeus.war.keep -DforceStdout 2>/dev/null || true)"
[[ "${KEEP}" == "null"* ]] && KEEP=""
KEEP_PREFIXES=()
[[ -n "${KEEP}" ]] && IFS='|' read -ra KEEP_PREFIXES <<< "${KEEP}"

# App'in runtime bağımlılık kapanışı
TMP="$(mktemp -d)"; trap 'rm -rf "${TMP}"' EXIT
echo ">> Kapsam denetimi: $(basename "${APP_DIR}") runtime bağımlılıkları vs com.zeus:${SLOT} module"
${MVN} -q -B -Dstyle.color=never dependency:list -DincludeScope=runtime \
    -DoutputFile="${TMP}/deps.txt" >/dev/null 2>&1

missing=()
while IFS= read -r line; do
    # ANSI temizle + baştaki boşluk
    line="$(printf '%s' "${line}" | sed 's/\x1b\[[0-9;]*m//g; s/^[[:space:]]*//')"
    # format: groupId:artifactId:jar:version:scope [ -- module ...]
    # CLASSIFIER'LI artefaktta bir alan FAZLADIR:
    #   groupId:artifactId:jar:classifier:version:scope   (ör. netty native transport'lar)
    # Alan sayısını saymadan sürümü hep 4. alandan okumak, classifier'lı jar'ları
    # "module'de yok" diye yanlış raporlar (jar adı da yanlış kurulur).
    [[ "${line}" =~ ^[^:]+:[^:]+:[^:]+:[^:]+:[^:[:space:]]+ ]] || continue
    coords="$(printf '%s' "${line}" | awk '{print $1}')"   # ' -- module ...' ekini at
    nf="$(printf '%s' "${coords}" | awk -F: '{print NF}')"
    gid="$(printf '%s' "${coords}" | cut -d: -f1)"
    aid="$(printf '%s' "${coords}" | cut -d: -f2)"
    cls=""
    if [[ "${nf}" -ge 6 ]]; then
        cls="$(printf '%s' "${coords}" | cut -d: -f4)"
        ver="$(printf '%s' "${coords}" | cut -d: -f5)"
    else
        ver="$(printf '%s' "${coords}" | cut -d: -f4)"
    fi
    [[ -z "${aid}" || -z "${ver}" ]] && continue
    # module'de olmayan küme (jakarta/zeus/lombok/jarmode) → atla
    [[ "${aid}" =~ ${EXCLUDE_REGEX} ]] && continue
    if [[ -n "${cls}" ]]; then
        jar="${aid}-${ver}-${cls}.jar"
    else
        jar="${aid}-${ver}.jar"
    fi
    # zeus.war.keep ile WAR'a bundle edilenler → atla
    skip=0
    if [[ ${#KEEP_PREFIXES[@]} -gt 0 ]]; then
        for p in "${KEEP_PREFIXES[@]}"; do
            [[ -n "${p}" && "${jar}" == "${p}"* ]] && { skip=1; break; }
        done
    fi
    [[ "${skip}" == 1 ]] && continue
    # module'de fiziksel var mı? (SOAP tipinde com.zeus.soap da aranır)
    if [[ ! -f "${MODULE_DIR}/${jar}" ]]; then
        if [[ -n "${SOAP_MODULE_DIR}" && -f "${SOAP_MODULE_DIR}/${jar}" ]]; then
            continue
        fi
        if [[ -n "${cls}" ]]; then
            missing+=("${gid}:${aid}:${ver}:${cls}")
        else
            missing+=("${gid}:${aid}:${ver}")
        fi
    fi
done < "${TMP}/deps.txt"

if [[ ${#missing[@]} -gt 0 ]]; then
    echo "" >&2
    echo "❌ MODULE KAPSAM HATASI: aşağıdaki runtime bağımlılık(lar) paylaşımlı com.zeus:${SLOT} module'de YOK" >&2
    echo "   (WildFly'da NoClassDefFoundError'a yol açar):" >&2
    for m in "${missing[@]}"; do echo "     - ${m}" >&2; done
    echo "" >&2
    echo "   Çözüm:" >&2
    echo "     • Genel/paylaşılan lib  → zeus-fw/zeus-wildfly-module/pom.xml'e ekle," >&2
    echo "                               ./scripts/install-zeus-module.sh + WildFly restart" >&2
    echo "     • Yalnız bu app'e özel  → app pom'unda <zeus.war.keep>|<artifactId-öneki>-</zeus.war.keep>" >&2
    exit 1
fi

echo "✅ Module kapsamı tam: tüm runtime bağımlılıklar com.zeus:${SLOT} module'de (veya zeus.war.keep ile WAR'da)."