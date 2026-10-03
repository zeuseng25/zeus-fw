#!/usr/bin/env bash
# com.zeus'un TEMEL (base) dalı, kapanışındaki HER jakarta.*-api'nin WildFly server
# module'ünü export ediyor mu — YOKSA o API STRIPlenmiş ama HİÇBİR YERDEN sağlanmıyor demektir.
#
# NEDEN VAR (fix round 1, Important 1): CXF, zeus-wildfly-module'e (TEMEL com.zeus kapanışı)
# taşındığında jakarta.xml.ws-api ve jakarta.xml.soap-api bu kapanışa dahil oldu.
# install-zeus-module.sh'taki EXCLUDE_REGEX bu iki jar'ı module'e KOPYALANMAKTAN çıkarır
# (WildFly'ın kendi server module'lerinden export edilsinler diye — jakarta.*-api'lerin genel
# kuralı). Ama module.xml'in TEMEL (base/else) dalındaki "for m in ...; do" listesi yalnız
# SOAP dalına (--module soap) eklenmişti; TEMEL dala eklenmemişti. Sonuç: CXF'in ihtiyaç
# duyduğu iki API hiçbir yerden export edilmiyordu → CXF init'inde NoClassDefFoundError,
# hem standart tip CXF istemcileri (zeus-sms) hem SOAP tipi uygulamalar için (com.zeus.soap
# import'u bu API'leri DEPLOYMENT classloader'ına export eder, com.zeus'un KENDİSİNE değil —
# yani com.zeus.soap kurulu olması TEMEL com.zeus'un kendi eksikliğini KAPATMAZ).
#
# Bu guard İDDİAYI SABİT KODLAMAZ ("xml.ws, xml.soap eksik" gibi iki adı yazmaz) — bunun
# yerine EXPECTATİON'I TÜRETİR: zeus-wildfly-module'ün GERÇEK runtime kapanışındaki HER
# jakarta.*-api artifactId'sini bulur, install-zeus-module.sh'IN KENDİ EXCLUDE_REGEX'İYLE
# (tek kaynak — burada YENİDEN YAZILMAZ) hangilerinin module'e KONMADIĞINI belirler, bunları
# WildFly module adına çevirir (jakarta.<x>-api → jakarta.<x>.api) ve module.xml'in TEMEL
# (else) dalındaki "for m in ...; do" listesinde GEÇTİĞİNİ doğrular. Böylece CXF'ten SONRA
# kapanışa girecek bir sonraki jakarta.*-api de (ya da başka bir bağımlılığın sürükleyeceği
# herhangi biri) sessizce unutulamaz — guard onu OTOMATİK yakalar.
#
# Kullanım: ./scripts/test-com-zeus-jakarta-api-kapsama.sh
set -uo pipefail
FW_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
INSTALL_SCRIPT="${FW_ROOT}/scripts/install-zeus-module.sh"
fail=0

# --- 1) EXCLUDE_REGEX'i install-zeus-module.sh'IN KENDİSİNDEN oku (tek kaynak) ---
# Regex burada YENİDEN YAZILMAZ: iki dosya arasında elle senkron tutulan bir kopya, tam
# olarak bu guard'ın önlemeye çalıştığı türden bir drift kaynağı olurdu.
EXCLUDE_REGEX_LINE="$(grep -E '^EXCLUDE_REGEX=' "${INSTALL_SCRIPT}" || true)"
if [[ -z "${EXCLUDE_REGEX_LINE}" ]]; then
    echo "  ❌ ölçüm yapılamadı: install-zeus-module.sh içinde 'EXCLUDE_REGEX=' satırı bulunamadı (dosya değişti mi?)"
    exit 1
fi
# EXCLUDE_REGEX_LINE tek satırlık basit bir atama ('EXCLUDE_REGEX=...'); eval ile bu
# script'in KENDİ EXCLUDE_REGEX değişkenine yüklenir (aynı repo dosyasından, güvenilir).
eval "${EXCLUDE_REGEX_LINE}"
if [[ -z "${EXCLUDE_REGEX:-}" ]]; then
    echo "  ❌ ölçüm yapılamadı: EXCLUDE_REGEX boş çözüldü"
    exit 1
fi
echo "  ✅ EXCLUDE_REGEX install-zeus-module.sh'ten okundu"

# --- 2) TEMEL (base/else) dalının module.xml "for m in ...; do" listesini türet ---
# "--- 5) module.xml üret ---" ile modül dosyasının yazıldığı satır arasındaki tek
# if/else/fi bloğunun ELSE dalı = TEMEL (--module base, varsayılan). SOAP dalı (if kolu)
# bilerek dışarıda bırakılır — o zaten xml.ws/xml.soap'ı taşıyor, denetlenen o değil.
BASE_FOR_LOOP="$(
    sed -n '/# --- 5) module.xml üret ---/,/} > "\${MODULE_DIR}\/module.xml"/p' "${INSTALL_SCRIPT}" \
      | awk '/^    else$/ {in_else=1; next} /^    fi$/ {in_else=0} in_else {print}' \
      | grep -oE 'for m in [a-zA-Z. ]+;' | head -n1
)"
if [[ -z "${BASE_FOR_LOOP}" ]]; then
    echo "  ❌ ölçüm yapılamadı: module.xml'in TEMEL (else) dalındaki 'for m in ...; do' satırı bulunamadı"
    echo "     (install-zeus-module.sh'ın module.xml üretim bölümü yapısı değişmiş olabilir)"
    exit 1
fi
# Boşluklarla ayrılmış token listesi: " servlet annotation ... xml.ws xml.soap "
BASE_MODULES=" $(sed -E 's/^for m in //; s/;$//' <<< "${BASE_FOR_LOOP}") "
echo "  ✅ TEMEL dal 'for m in' listesi türetildi: ${BASE_MODULES# }"

# --- 3) zeus-wildfly-module'ün GERÇEK runtime kapanışını çöz ---
out="$(mktemp)"; err="$(mktemp)"
trap 'rm -f "${out}" "${err}"' EXIT
if ! ( cd "${FW_ROOT}/zeus-wildfly-module" && mvn -q -B -Dstyle.color=never dependency:list \
        -DincludeScope=runtime -DoutputFile="${out}" >/dev/null 2>"${err}" ); then
    echo "  ❌ ölçüm yapılamadı: mvn dependency:list başarısız (zeus-wildfly-module)"
    cat "${err}" >&2
    exit 1
fi
if [[ ! -s "${out}" ]]; then
    echo "  ❌ ölçüm yapılamadı: dependency:list çıktısı BOŞ (zeus-wildfly-module'ün 150+ bağımlılığı var; boş çıktı ölçüm hatasıdır)"
    cat "${err}" >&2
    exit 1
fi

# format: groupId:artifactId:jar[:classifier]:version:scope[ -- module ...]
JAKARTA_APIS="$(
    sed 's/\x1b\[[0-9;]*m//g; s/^[[:space:]]*//' "${out}" \
      | grep -E '^[^:]+:[^:]+:[^:]+:' \
      | awk -F: '{print $2}' \
      | grep -E '^jakarta\.[a-zA-Z.]+-api$' \
      | sort -u
)"
if [[ -z "${JAKARTA_APIS}" ]]; then
    echo "  ❌ ölçüm yapılamadı: kapanışta HİÇ jakarta.*-api artifactId'si bulunamadı (beklenmiyor — en az servlet/persistence/vb. olmalı)"
    exit 1
fi
n_apis="$(grep -c . <<< "${JAKARTA_APIS}")"
echo "  ✅ kapanışta ${n_apis} adet jakarta.*-api artifactId'si bulundu"

# --- 4) HER jakarta.*-api için: EXCLUDE_REGEX onu STRIPliyorsa, TEMEL dal onu export etmeli ---
# EXCLUDE_REGEX gerçek jar dosya adına (artifactId-version.jar) göre eşleşir; sürümü
# bilmediğimiz için sentetik bir dosya adıyla ('<artifactId>-0.jar') test ediyoruz —
# EXCLUDE_REGEX'in artifactId kısmı sürümden ÖNCEKİ '-' ile ayrılır, bu yeterli.
inspected=0
asserted=0
while IFS= read -r aid; do
    [[ -z "${aid}" ]] && continue
    inspected=$((inspected + 1))
    synth="${aid}-0.jar"
    if [[ ! "${synth}" =~ ${EXCLUDE_REGEX} ]]; then
        # EXCLUDE_REGEX bu jar'ı STRIPLEMİYOR → module'e KOPYALANIYOR (resource-root olarak
        # module.xml'de zaten var) → server module export'una ihtiyaç YOK. Denetim dışı
        # (ör. jakarta.mail-api — bugün EXCLUDE_REGEX'in enumerated listesinde değil).
        echo "  ·  ${aid} module'e KOPYALANIYOR (EXCLUDE_REGEX STRIPlemiyor) — export gerekmiyor, atlandı"
        continue
    fi
    asserted=$((asserted + 1))
    # jakarta.<x>-api → <x>  (örn. jakarta.xml.ws-api → xml.ws, jakarta.annotation-api → annotation)
    m="${aid#jakarta.}"; m="${m%-api}"
    if [[ "${BASE_MODULES}" == *" ${m} "* ]]; then
        echo "  ✅ ${aid} → jakarta.${m}.api TEMEL dalda export ediliyor"
    else
        echo "  ❌ ${aid} EXCLUDE_REGEX'te STRIPleniyor (module'e kopyalanmıyor) AMA jakarta.${m}.api TEMEL dalın 'for m in' listesinde YOK"
        echo "     → server module'ü de kopya da yok = CXF/ilgili kod NoClassDefFoundError ile düşer."
        echo "     Çözüm: install-zeus-module.sh'taki TEMEL (else) dalının 'for m in' listesine '${m}' ekleyin."
        fail=1
    fi
done <<< "${JAKARTA_APIS}"

echo "  >> ${inspected} jakarta.*-api incelendi, ${asserted} tanesi STRIPleniyor ve TEMEL dal export'una muhtaç (denetlendi)"
exit ${fail}
