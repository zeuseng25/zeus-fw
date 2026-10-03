#!/usr/bin/env bash
#
# Script sertleştirme testi (PLATFORM scripti).
#
# İki şeyi denetler:
#   1) `set -e` kullanan platform script'lerinin HER BİRİ, düşen komutu (dosya:satır +
#      komut) BASAN bir `trap ... ERR` içeriyor mu — "sessizce ölme" kuralı.
#      (run-guards.sh ve test-*.sh KASITLI olarak `-e`'siz; bu testin kapsamı DIŞINDA —
#      onlar hataları TOPLAYIP sonunda raporluyor, `-e` bu yeteneği yok ederdi.)
#   2) install-zeus-module.sh'taki HER `mvn` çağrısı `if !` ile sarılı mı (kontrolsüz
#      `mvn` = hata sessizce -e'ye terk edilmiş, kendi mesajı yok demektir).
#
# Kullanım: ./scripts/test-script-hardening.sh
#
set -uo pipefail

FW_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
fail=0

check() {  # $1=aciklama  $2=0(basarili)/1(basarisiz)
    if [[ "$2" == "0" ]]; then
        echo "  ✅ $1"
    else
        echo "  ❌ $1"
        fail=1
    fi
}

echo ">> 1) ERR trap: set -e kullanan script'ler sessizce ölmemeli"
ERR_TRAP_SCRIPTS=(
    "install-zeus-module.sh"
    "verify-staging.sh"
    "slot-inventory.sh"
    "generate-war-excludes.sh"
    "verify-module-coverage.sh"
)
for s in "${ERR_TRAP_SCRIPTS[@]}"; do
    f="${FW_ROOT}/scripts/${s}"
    if [[ ! -f "${f}" ]]; then
        check "${s}: dosya bulunamadı" 1
        continue
    fi
    # set -e kullandığını doğrula (yanlışlıkla test edilen script'ler bu varsayımı bırakırsa
    # test kendi kendini yanlış yeşile düşürmesin).
    if ! grep -qE '^\s*set\s+-e' "${f}"; then
        check "${s}: 'set -e' bekleniyordu ama yok — test listesi/script güncelliğini yitirmiş olabilir" 1
        continue
    fi
    # ERR sözcüğü tam bir TOKEN olarak aranır (kelime sınırıyla) — 'MVN_ERR' gibi ERR
    # ALT DİZESİ içeren değişken adları (ör. install-zeus-module.sh'ta olmayan ama
    # verify-module-coverage.sh'ta 'MVN_ERR' EXIT trap'inde geçen kalıp) yanlış pozitif
    # üretmesin.
    if grep -qE 'trap[[:space:]].*[[:space:]]ERR([[:space:]]|$)' "${f}"; then
        check "${s}: ERR trap var" 0
    else
        check "${s}: ERR trap YOK — set -e ile düşen komut sessizce ölür" 1
    fi
done

echo ""
echo ">> 2) install-zeus-module.sh: kontrolsüz 'mvn' çağrısı kalmamalı"
IZM="${FW_ROOT}/scripts/install-zeus-module.sh"
if [[ ! -f "${IZM}" ]]; then
    check "install-zeus-module.sh bulunamadı" 1
else
    # GERÇEK çağrı satırları: satırın İLK token'ı (baştaki boşluk / opsiyonel 'if !'
    # hariç) 'mvn'. Bu, HATA mesajı metinleri içindeki "...'mvn dependency:get'..." gibi
    # serbest metin geçişlerini (yanlış pozitif) SAYMAZ.
    bare_mvn_lines="$(grep -nE '^[[:space:]]*mvn[[:space:]]' "${IZM}" || true)"
    mvn_call_count="$(grep -cE '^[[:space:]]*(if[[:space:]]*![[:space:]]*)?mvn[[:space:]]' "${IZM}" || true)"
    guarded_count="$(grep -cE '^[[:space:]]*if[[:space:]]*![[:space:]]*mvn[[:space:]]' "${IZM}" || true)"

    if [[ -n "${bare_mvn_lines}" ]]; then
        echo "     kontrolsüz mvn satır(lar)ı:"
        sed 's/^/       /' <<< "${bare_mvn_lines}"
        check "install-zeus-module.sh: her 'mvn' çağrısı 'if !' ile sarılı" 1
    elif [[ "${mvn_call_count}" -lt 1 ]]; then
        check "install-zeus-module.sh: hiç 'mvn' çağrısı bulunamadı — test script'i güncelliğini yitirmiş olabilir" 1
    elif [[ "${guarded_count}" -lt "${mvn_call_count}" ]]; then
        check "install-zeus-module.sh: ${mvn_call_count} mvn çağrısından yalnız ${guarded_count} tanesi 'if !' ile sarılı" 1
    else
        check "install-zeus-module.sh: ${mvn_call_count}/${mvn_call_count} mvn çağrısı 'if !' ile sarılı" 0
    fi

    # Her sarılmış mvn çağrısının KENDİ hata mesajını ürettiğini de doğrula — brief'in
    # "her biri kendi hata mesajını versin" şartı. Sezgisel ölçüm: dosyadaki mvn'e özgü
    # "HATA:" satırlarının sayısı, mvn çağrı sayısına eşit ya da fazla olmalı.
    own_msg_count="$(grep -cE 'HATA:.*(mvn|dependency:copy-dependencies|dependency:get)' "${IZM}" || true)"
    if [[ "${own_msg_count}" -ge "${mvn_call_count:-0}" && "${mvn_call_count:-0}" -ge 1 ]]; then
        check "install-zeus-module.sh: her mvn çağrısı için özel HATA mesajı var (${own_msg_count} mesaj / ${mvn_call_count} çağrı)" 0
    else
        check "install-zeus-module.sh: mvn çağrılarına özel HATA mesajı eksik (${own_msg_count} mesaj / ${mvn_call_count} çağrı)" 1
    fi
fi

echo ""
if [[ "${fail}" == "0" ]]; then
    echo "✅ test-script-hardening.sh: tüm kontroller geçti."
else
    echo "❌ test-script-hardening.sh: en az bir kontrol başarısız." >&2
fi
exit "${fail}"
