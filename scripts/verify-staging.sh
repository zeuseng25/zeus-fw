#!/usr/bin/env bash
#
# Staging doğrulama geçidi (PLATFORM scripti).
#
# BOM'daki bir sürüm değişikliğini (CVE yaması / yükseltme) prod'a çıkmadan önce
# staging WildFly üzerinde uçtan uca doğrular (bkz. gelistirmeler/09 adım 3 "STAGING"):
#
#   1) mevcut com.zeus module'ünü yedekle (rollback için)
#   2) module'ü BOM'dan yeniden üret (install-zeus-module.sh)
#   3) staging WildFly'ı restart et (module.xml cache'li — restart ŞART)
#   4) verilen HER uygulamayı deploy et (app'in kendi deploy.sh'ı; marker doğrulaması dahil)
#   5) her uygulamanın smoke testini koş (app'in scripts/smoke-test.sh'ı; varsa)
#   6) hepsi yeşilse doğrulanmış module'den PROMOTE ARTIFACT'ı (tar.gz) üret
#      → prod node'larına AYNI kopya dağıtılır; node'lar arası sürüm kayması (drift) önlenir
#
# Kullanım (script sunucuyu RESTART ettiği için WILDFLY_HOME bilinçli olarak zorunludur;
# yanlışlıkla varsayılan/prod sunucuyu hedeflememek için):
#   WILDFLY_HOME=/path/staging-wildfly ./scripts/verify-staging.sh <app-dir> [app-dir...]
#
# Opsiyonel env:
#   PORT_OFFSET=100   staging aynı makinede başka WildFly ile yan yana ise
#                     (http 8080+offset, mgmt 9990+offset; standalone.sh'a offset geçilir)
#   NO_BUILD=1        WAR'lar CI'da zaten build edildiyse deploy.sh --no-build ile deploy et
#   SLOT=1.1.0        VERSİYONLU SLOT AKIŞI (restart'sız — bkz. gelistirmeler/10):
#                     module main'e değil yeni slot'a kurulur (immutable), sunucu restart
#                     EDİLMEZ (ayaktaysa dokunulmaz, kapalıysa başlatılır); her app'in WAR'ının
#                     gerçekten bu slot'u hedeflediği doğrulanır (yeni parent'la build edilmemiş
#                     app gate'i kırar). Önkoşul: zeus-parent'ta zeus.module.slot=$SLOT set edilip
#                     parent yayınlanmış olmalı (app build'leri descriptor'ı bu slot'la üretir).
#
# Varsayımlar (mevcut konvansiyon): her app'te scripts/deploy.sh vardır ve WAR'ı
# <app-dizin-adı>.war olarak deploy eder → context path /<app-dizin-adı>.
#
set -euo pipefail

ZEUS_FW_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [[ -z "${WILDFLY_HOME:-}" ]]; then
    echo "HATA: WILDFLY_HOME set edilmeli (script hedef sunucuyu restart eder)." >&2
    echo "      WILDFLY_HOME=/path/staging-wildfly $0 <app-dir> [app-dir...]" >&2
    exit 2
fi
export WILDFLY_HOME

if [[ $# -lt 1 ]]; then
    echo "HATA: en az bir uygulama dizini verin: $0 <app-dir> [app-dir...]" >&2
    exit 2
fi

SLOT="${SLOT:-main}"
MODULE_DIR="${WILDFLY_HOME}/modules/com/zeus/${SLOT}"
PORT_OFFSET="${PORT_OFFSET:-0}"
HTTP_PORT=$((8080 + PORT_OFFSET))
MGMT_PORT=$((9990 + PORT_OFFSET))
STAMP="$(date +%Y%m%d-%H%M%S)"
SERVER_LOG="${WILDFLY_HOME}/standalone/log/server.log"

# --- Ön kontrol: app dizinleri ve deploy.sh'ları var mı ---
APPS=()
for app in "$@"; do
    app="$(cd "${app}" 2>/dev/null && pwd)" || { echo "HATA: dizin yok: ${app}" >&2; exit 2; }
    [[ -x "${app}/scripts/deploy.sh" ]] || { echo "HATA: ${app}/scripts/deploy.sh yok/çalıştırılamıyor" >&2; exit 2; }
    APPS+=("${app}")
done

echo ">> Staging doğrulama: ${WILDFLY_HOME} (http:${HTTP_PORT} mgmt:${MGMT_PORT}, slot: ${SLOT})"
echo ">> Uygulamalar: $(for a in "${APPS[@]}"; do printf '%s ' "$(basename "$a")"; done)"

wf_pid() { pgrep -f "jboss.home.dir=${WILDFLY_HOME}" | head -n1 || true; }

if [[ "${SLOT}" == "main" ]]; then
    # ================= MAIN AKIŞI (mutable slot: yedekle → üret → RESTART) =================

    # --- 1) Mevcut module'ü yedekle (sorun çıkarsa: yedeği geri kopyala + restart) ---
    if [[ -d "${MODULE_DIR}" ]]; then
        BACKUP="${MODULE_DIR%/main}/main.bak-${STAMP}"
        cp -a "${MODULE_DIR}" "${BACKUP}"
        echo ">> Yedek alındı: ${BACKUP}"
    fi

    # --- 2) Module'ü BOM'dan yeniden üret ---
    "${ZEUS_FW_DIR}/scripts/install-zeus-module.sh"

    # --- 3) Staging WildFly restart (yüklü module.xml cache'li olduğundan şart) ---
    # 3a) Ayaktaysa kapat: önce CLI ile nazikçe, olmazsa süreci sonlandır
    PID="$(wf_pid)"
    if [[ -n "${PID}" ]]; then
        echo ">> WildFly kapatılıyor (PID ${PID})..."
        if ! "${WILDFLY_HOME}/bin/jboss-cli.sh" --connect --controller="localhost:${MGMT_PORT}" \
                command=:shutdown >/dev/null 2>&1; then
            kill "${PID}" 2>/dev/null || true
        fi
        for _ in $(seq 1 30); do
            [[ -z "$(wf_pid)" ]] && break
            sleep 1
        done
        if [[ -n "$(wf_pid)" ]]; then
            echo "HATA: WildFly kapanmadı (PID $(wf_pid)); elle kapatıp yeniden deneyin." >&2
            exit 1
        fi
    fi
    NEED_START=1
else
    # ================= SLOT AKIŞI (immutable slot: kur → RESTART YOK) =================

    # --- 1) Yedek gerekmez: yeni slot mevcut hiçbir module'e dokunmaz; rollback = app'lerin
    #        önceki slot'u göstermesi (module geri kopyalama cerrahisi yok).

    # --- 2) Slot'u kur (zaten kuruluysa: immutable — mevcut içerikle doğrulanır) ---
    if [[ -d "${MODULE_DIR}" ]]; then
        echo ">> com.zeus:${SLOT} zaten kurulu (immutable) — mevcut içerikle doğrulanacak."
    else
        "${ZEUS_FW_DIR}/scripts/install-zeus-module.sh" --slot "${SLOT}"
    fi

    # --- 3) RESTART YOK: sunucu ayaktaysa dokunulmaz (yeni slot ilk referansta yüklenir);
    #        kapalıysa başlatılır.
    if [[ -n "$(wf_pid)" ]]; then
        echo ">> WildFly zaten ayakta — slot akışında restart edilmez (kesintisiz doğrulama)."
        NEED_START=0
    else
        NEED_START=1
    fi
fi

# --- Başlat (gerekliyse) ve management portunu bekle ---
if [[ "${NEED_START}" == "1" ]]; then
    JBOSS_ARGS=()
    [[ "${PORT_OFFSET}" != "0" ]] && JBOSS_ARGS+=("-Djboss.socket.binding.port-offset=${PORT_OFFSET}")
    BOOT_LOG="${WILDFLY_HOME}/standalone/log/verify-staging-console.out"
    echo ">> WildFly başlatılıyor..."
    nohup "${WILDFLY_HOME}/bin/standalone.sh" ${JBOSS_ARGS[@]+"${JBOSS_ARGS[@]}"} > "${BOOT_LOG}" 2>&1 &
    UP=0
    for _ in $(seq 1 60); do
        if curl -s -o /dev/null --max-time 2 "http://localhost:${MGMT_PORT}"; then
            UP=1; break
        fi
        sleep 2
    done
    if [[ "${UP}" != "1" ]]; then
        echo "HATA: WildFly 120 sn içinde açılmadı. Boot log: ${BOOT_LOG}" >&2
        exit 1
    fi
fi
echo ">> WildFly ayakta (mgmt:${MGMT_PORT})"

# --- 4+5) Her uygulama: deploy + smoke (biri düşse de kalanlar denenir → tam gate raporu) ---
DEPLOY_FLAGS=()
[[ "${NO_BUILD:-0}" == "1" ]] && DEPLOY_FLAGS+=(--no-build)
declare -a RESULTS=()
GATE_FAIL=0
for app in "${APPS[@]}"; do
    name="$(basename "${app}")"
    echo ""
    echo "=== ${name} ==="
    if ! "${app}/scripts/deploy.sh" ${DEPLOY_FLAGS[@]+"${DEPLOY_FLAGS[@]}"}; then
        RESULTS+=("❌ ${name}: DEPLOY başarısız")
        GATE_FAIL=1
        continue
    fi
    # Slot-hedef doğrulaması: app'in WAR'ı doğrulanan slot'u mu gösteriyor?
    # (Yeni parent'la build edilmemiş bir app, ESKİ slot'la yeşil görünüp gate'i yanıltmasın.)
    WAR_FILE="$(ls -t "${app}"/target/*.war 2>/dev/null | head -n1 || true)"
    if [[ -n "${WAR_FILE}" ]]; then
        APP_SLOT="$(unzip -p "${WAR_FILE}" WEB-INF/jboss-deployment-structure.xml 2>/dev/null \
            | grep 'name="com\.zeus"' | sed -n 's/.*slot="\([^"]*\)".*/\1/p' || true)"
        [[ -z "${APP_SLOT}" ]] && APP_SLOT="main"
        if [[ "${APP_SLOT}" != "${SLOT}" ]]; then
            RESULTS+=("❌ ${name}: WAR com.zeus:${APP_SLOT} hedefliyor, doğrulanan slot com.zeus:${SLOT} (parent'ı zeus.module.slot=${SLOT} üreten sürüme yükseltip yeniden build edin)")
            GATE_FAIL=1
            continue
        fi
    fi
    SMOKE="${app}/scripts/smoke-test.sh"
    if [[ -x "${SMOKE}" ]]; then
        if SERVER_LOG="${SERVER_LOG}" "${SMOKE}" "http://localhost:${HTTP_PORT}/${name}"; then
            RESULTS+=("✅ ${name}: deploy + smoke geçti")
        else
            RESULTS+=("❌ ${name}: SMOKE TEST başarısız")
            GATE_FAIL=1
        fi
    else
        # smoke scripti olmayan app yalnız deploy (context açılışı) düzeyinde doğrulanmış olur
        RESULTS+=("⚠  ${name}: deploy geçti, smoke-test.sh yok (davranış doğrulanmadı)")
    fi
done

# --- Gate raporu ---
echo ""
echo "==================== GATE RAPORU ===================="
for r in "${RESULTS[@]}"; do echo "  ${r}"; done
echo "====================================================="

if [[ "${GATE_FAIL}" != "0" ]]; then
    echo "❌ GATE FAILED — prod'a promote ETMEYİN. Geri almak için:" >&2
    if [[ "${SLOT}" == "main" ]]; then
        [[ -n "${BACKUP:-}" ]] && echo "   rm -rf '${MODULE_DIR}' && cp -a '${BACKUP}' '${MODULE_DIR}' + WildFly restart" >&2
    else
        echo "   (slot akışı: mevcut module'lere dokunulmadı; app'ler önceki slot'u gösteren" >&2
        echo "    parent sürümüyle yeniden build+deploy edilir. Slot dizini isterseniz kalabilir.)" >&2
    fi
    exit 1
fi

# --- 6) Promote artifact'ı: staging'de doğrulanan module'ün birebir kopyası ---
DIST_DIR="${ZEUS_FW_DIR}/target"
mkdir -p "${DIST_DIR}"
ARTIFACT="${DIST_DIR}/com-zeus-module-${SLOT}-${STAMP}.tar.gz"
tar -czf "${ARTIFACT}" -C "${WILDFLY_HOME}/modules" "com/zeus/${SLOT}"
echo ""
echo "✅ GATE PASSED"
echo ">> Promote artifact'ı: ${ARTIFACT}"
if [[ "${SLOT}" == "main" ]]; then
    echo ">> Prod'a çıkış (her node'da, rolling — node'u LB'den çekerek):"
    echo "   cp -a \$WILDFLY_HOME/modules/com/zeus/main \$WILDFLY_HOME/modules/com/zeus/main.bak-${STAMP}  # rollback yedeği"
    echo "   rm -rf \$WILDFLY_HOME/modules/com/zeus/main && tar -xzf $(basename "${ARTIFACT}") -C \$WILDFLY_HOME/modules"
    echo "   WildFly restart + WAR redeploy + smoke test"
else
    echo ">> Prod'a çıkış (RESTART YOK — bkz. gelistirmeler/10 rollout akışı):"
    echo "   tar -xzf $(basename "${ARTIFACT}") -C \$WILDFLY_HOME/modules      # yeni slot dizini; mevcutlara dokunmaz"
    echo "   Her app kendi hızında: parent'ı zeus.module.slot=${SLOT} üreten sürüme yükselt + deploy.sh"
    echo "   Rollback (app bazında): parent sürümünü geri al + redeploy"
    echo "   Kapanış: ./scripts/slot-inventory.sh 'kullanan yok' deyince eski slot'u sil (restart penceresinde)"
fi