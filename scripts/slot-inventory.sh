#!/usr/bin/env bash
#
# com.zeus SLOT ENVANTERİ (PLATFORM scripti).
#
# Bir WildFly sunucusunda "hangi slot'lar kurulu" ve "hangi uygulama hangi slot'u kullanıyor"
# sorularını cevaplar; slot politikalarını denetler (bkz. gelistirmeler/10 "Politikalar"):
#
#   - Uygulama → KURULU OLMAYAN slot'a işaret ediyorsa            → HATA   (exit 2)
#   - Kurulu slot sayısı > 2 ise (max-2-aktif-slot politikası)    → UYARI  (exit 1)
#   - Hiçbir app'in kullanmadığı versiyonlu slot                  → silinebilir aday (bilgi)
#
# CVE kapanış takibinin aracıdır: "tüm app'ler yeni slot'a geçti mi?" buradan görülür;
# eski slot ancak envanter 'kullanan yok' dediğinde silinir (silme: restart penceresinde).
#
# Kullanım:
#   ./scripts/slot-inventory.sh
#   WILDFLY_HOME=/path/staging-wildfly ./scripts/slot-inventory.sh
#
set -euo pipefail
# Sessiz ölüm YASAK: set -e ile düşen her komut nerede düştüğünü söylesin.
trap 'rc=$?; echo "HATA: ${BASH_SOURCE[0]}:${LINENO} — komut başarısız (çıkış ${rc}): ${BASH_COMMAND}" >&2' ERR

WILDFLY_HOME="${WILDFLY_HOME:-/Users/omer/workspaces/intellij/wildfly-41/wildfly-41.0.0.Final}"
ZEUS_MODULES="${WILDFLY_HOME}/modules/com/zeus"
DEPLOYMENTS="${WILDFLY_HOME}/standalone/deployments"

if [[ ! -d "${ZEUS_MODULES}" ]]; then
    echo "HATA: com.zeus module dizini yok: ${ZEUS_MODULES}" >&2
    exit 2
fi

# --- 1) Kurulu slot'lar (yedek dizinleri *.bak-* envantere girmez) ---
SLOTS=()
for dir in "${ZEUS_MODULES}"/*/; do
    slot="$(basename "${dir}")"
    [[ "${slot}" == *.bak-* ]] && continue
    [[ -f "${dir}/module.xml" ]] || continue
    SLOTS+=("${slot}")
done

# --- 2) Deploy edilmiş uygulamalar → kullandıkları slot ---
# Slot, WAR içindeki (framework'ün ürettiği) jboss-deployment-structure.xml'den okunur;
# slot attribute'u yoksa WildFly varsayılanı 'main' kabul edilir.
APP_NAMES=(); APP_SLOTS=(); APP_STATES=()
if [[ -d "${DEPLOYMENTS}" ]]; then
    for war in "${DEPLOYMENTS}"/*.war; do
        [[ -e "${war}" ]] || continue
        name="$(basename "${war}")"
        # (Yutma denetimi 1/4: DÜZELTİLDİ — eskiden unzip 2>/dev/null || true tek başına
        #  hem "WAR'da bu entry yok" (normal: com.zeus kullanmayan app) hem "WAR bozuk/
        #  okunamıyor" (gerçek hata) durumlarını AYNI ŞEKİLDE "-" slot'una düşürüyordu; bu
        #  salt-raporlama bir envanter aracı olsa da, bozuk bir WAR'ın sessizce "com.zeus
        #  kullanmıyor" gibi görünmesi yanıltıcıdır. Önce zip'in okunabilirliği ayrıca
        #  doğrulanır; yalnız OKUNABİLİR bir zip'te entry/satır bulunamaması hâlâ meşru
        #  şekilde opsiyoneldir (aşağıdaki 2/4).
        if ! unzip -l "${war}" >/dev/null 2>/dev/null; then
            echo "  ⚠  ${name}: WAR okunamadı (bozuk/erişilemez zip) — slot '?' olarak işaretlendi." >&2
            slot="?"
        else
            desc="$(unzip -p "${war}" WEB-INF/jboss-deployment-structure.xml 2>/dev/null || true)"
            # (Yutma denetimi 2/4: || true OPSİYONEL — zip okunabilir olduğu hâlde grep'in
            #  eşleşme BULAMAMASI (çıkış 1) com.zeus kullanmayan/descriptor'sız normal bir
            #  deployment anlamına gelir; bu bir hata değildir.)
            line="$(printf '%s' "${desc}" | grep 'name="com\.zeus"' || true)"
            if [[ -z "${line}" ]]; then
                slot="-"          # com.zeus kullanmayan / descriptor'sız deployment
            else
                slot="$(printf '%s' "${line}" | sed -n 's/.*slot="\([^"]*\)".*/\1/p')"
                [[ -z "${slot}" ]] && slot="main"
            fi
        fi
        state="?"
        [[ -f "${war}.deployed"   ]] && state="deployed"
        [[ -f "${war}.failed"     ]] && state="FAILED"
        [[ -f "${war}.isdeploying" ]] && state="deploying"
        APP_NAMES+=("${name}"); APP_SLOTS+=("${slot}"); APP_STATES+=("${state}")
    done
fi

# --- 3) Rapor ---
echo "==================== com.zeus SLOT ENVANTERİ ===================="
echo "Sunucu: ${WILDFLY_HOME}"
echo ""
echo "Kurulu slot'lar:"
for slot in ${SLOTS[@]+"${SLOTS[@]}"}; do
    dir="${ZEUS_MODULES}/${slot}"
    # (Yutma denetimi 3/4 ve 4/4: 2>/dev/null OPSİYONEL — 'dir' birkaç satır önce AYNI
    #  dizin taramasından geldi (satır ~32-37), bu yüzden normal koşuda var olmalı; burada
    #  yalnızca elle müdahale/yarış durumuna (script koşarken dizin silinmesi, izin
    #  değişikliği) karşı DEFANSİF davranılıyor. find/du başarısız olursa jars/size boş
    #  basar (0 jar / boş boyut) — bu, salt-raporlama bir envanterde script'i durduracak
    #  kritik bir hata değildir; asıl slot varlığı zaten SLOTS dizisinde module.xml
    #  kontrolüyle doğrulanmıştır (satır ~35).)
    jars="$(find "${dir}" -maxdepth 1 -name '*.jar' 2>/dev/null | wc -l | tr -d ' ')"
    size="$(du -sh "${dir}" 2>/dev/null | cut -f1)"
    users=0
    for i in ${APP_SLOTS[@]+"${!APP_SLOTS[@]}"}; do
        [[ "${APP_SLOTS[$i]}" == "${slot}" ]] && users=$((users + 1))
    done
    printf '  com.zeus:%-16s %3s jar  %6s  kullanan app: %d\n' "${slot}" "${jars}" "${size}" "${users}"
done
echo ""
echo "Deploy edilmiş uygulamalar:"
if [[ ${#APP_NAMES[@]} -eq 0 ]]; then
    echo "  (yok)"
else
    for i in "${!APP_NAMES[@]}"; do
        printf '  %-32s → com.zeus:%-16s [%s]\n' "${APP_NAMES[$i]}" "${APP_SLOTS[$i]}" "${APP_STATES[$i]}"
    done
fi

# --- 4) Politika denetimleri ---
EXIT=0
echo ""
echo "Denetimler:"

# 4a) Kurulu olmayan slot'a işaret eden app → HATA
# "?" (WAR okunamadı) "-" (com.zeus kullanmıyor) gibi muaftır: slot bilgisi hiç
# ÖLÇÜLEMEDİĞİNDEN "kurulu değil" iddiası yanlış olur; okunamama zaten yukarıda (1/4)
# ayrı bir ⚠ ile bildirildi.
for i in ${APP_NAMES[@]+"${!APP_NAMES[@]}"}; do
    slot="${APP_SLOTS[$i]}"
    [[ "${slot}" == "-" || "${slot}" == "?" ]] && continue
    found=0
    for s in ${SLOTS[@]+"${SLOTS[@]}"}; do [[ "${s}" == "${slot}" ]] && { found=1; break; }; done
    if [[ "${found}" == "0" ]]; then
        echo "  ❌ ${APP_NAMES[$i]} → com.zeus:${slot} KURULU DEĞİL (app açılışta kırılır!)"
        echo "     Çözüm: ./scripts/install-zeus-module.sh --slot ${slot}   (restart gerekmez)"
        EXIT=2
    fi
done

# 4b) Max-2-aktif-slot politikası → UYARI
if [[ ${#SLOTS[@]} -gt 2 ]]; then
    echo "  ⚠  ${#SLOTS[@]} slot kurulu — politika: aynı anda en fazla 2 aktif slot."
    echo "     Geçişi tamamlayıp eski slot'ları silin (bkz. 'silinebilir aday' listesi)."
    [[ "${EXIT}" == "0" ]] && EXIT=1
fi

# 4c) Kullanılmayan versiyonlu slot'lar → silinebilir aday (main önerilmez; varsayılan slot)
for slot in ${SLOTS[@]+"${SLOTS[@]}"}; do
    [[ "${slot}" == "main" ]] && continue
    users=0
    for i in ${APP_SLOTS[@]+"${!APP_SLOTS[@]}"}; do
        [[ "${APP_SLOTS[$i]}" == "${slot}" ]] && users=$((users + 1))
    done
    if [[ "${users}" == "0" ]]; then
        echo "  ♻  com.zeus:${slot} — kullanan app yok → silinebilir aday."
        echo "     Silme, bir restart penceresinde yapılmalı (yüklü module'ün jar'ları tembel açılır;"
        echo "     sunucu o slot'u daha önce yüklediyse dizini canlıyken silmek riskli olabilir):"
        echo "       rm -rf '${ZEUS_MODULES}/${slot}'"
    fi
done

if [[ "${EXIT}" == "0" ]]; then
    echo "  ✅ Politika ihlali yok."
fi
echo "================================================================="
exit "${EXIT}"