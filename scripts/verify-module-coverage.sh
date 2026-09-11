#!/usr/bin/env bash
#
# Deploy ön-kontrolü (PLATFORM scripti).
#
# ÜÇ ŞEYİ denetler:
#   1) Uygulamanın hedeflediği com.zeus (bir SOAP ENDPOINT'İ YAYINLAYAN uygulamalarda ayrıca
#      com.zeus.soap) slot'u sunucuda KURULU mu?
#   2) WAR'da framework'ün ÜRETTİĞİ jboss-deployment-structure.xml var mı?
#   3) TERS KAPSAM: WAR'dan SİLİNEN her artifactId, hedeflenen slot'ta GERÇEKTEN VAR mı?
#
# NOT: "uygulamanın bağımlılığı module'de var mı?" kontrolü KALDIRILDI. Denylist
# paketlemesinden sonra module'de olmayan bağımlılık WAR'da taşınır (gelistirmeler/
# 19-war-paketleme-module-farkindaligi.md); onu eksik saymak yanlış pozitiftir. Bunun
# TERSİ ise kabul edilmiş bir taviz DEĞİLDİR — bkz. (4) numaralı kontrolün başlığı.
#
# Kullanım:
#   ./scripts/verify-module-coverage.sh [app-dizini]   (varsayılan: cwd)
#   WILDFLY_HOME=/path/staging ./scripts/verify-module-coverage.sh /path/app
#
set -euo pipefail
# Sessiz ölüm YASAK: set -e ile düşen her komut nerede düştüğünü söylesin.
trap 'rc=$?; echo "HATA: ${BASH_SOURCE[0]}:${LINENO} — komut başarısız (çıkış ${rc}): ${BASH_COMMAND}" >&2' ERR

# FW_ROOT, APP_DIR'e cd EDİLMEDEN ÖNCE çözülür (sabit kuyruğu üreticiden okumak için).
FW_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WILDFLY_HOME="${WILDFLY_HOME:-/Users/omer/workspaces/intellij/wildfly-41/wildfly-41.0.0.Final}"
APP_DIR="${1:-$(pwd)}"

cd "${APP_DIR}"
MVN="mvn"

MVN_ERR="$(mktemp)"
trap 'rm -f "${MVN_ERR}"' EXIT

# Bir Maven property'sini çözer. ÇIKIŞ KODU KORUNUR — çağıran ayırt etmek ZORUNDADIR:
#   rc=0 → "çözüldü" (değer 'null object or invalid expression' ise property TANIMSIZ),
#   rc≠0 → "ÇÖZÜLEMEDİ" (mvn düştü: offline, bozuk pom, eksik parent…).
# Eskiden her üç çağrı da `|| true` ile yutuluyordu; mvn herhangi bir sebeple düşünce
# PKG_EXCLUDES boş kalıyor, script bunu "self-contained WAR" sanıp deploy gate'ini
# EXIT 0 ile YEŞİL geçiriyordu — ölçüm hatasını başarı olarak raporlayan sahte yeşil
# (final review, Important 1). Aynı deliği generate-war-excludes.sh'te de kapattık.
eval_prop() {  # $1 = property adı
    ${MVN} -q -B -Dstyle.color=never help:evaluate -Dexpression="$1" -DforceStdout 2>"${MVN_ERR}"
}

# Çözülemeyen property = ölçüm hatası = HARD FAILURE. Deploy gate'i "bilmiyorum"u
# "sorun yok" diye raporlayamaz.
prop_or_die() {  # $1 = property adı; stdout = değer ('null…' → boş)
    local name="$1" val
    if ! val="$(eval_prop "${name}")"; then
        echo "HATA: '${name}' property'si ÇÖZÜLEMEDİ — 'mvn help:evaluate' başarısız oldu." >&2
        echo "      Bu bir ÖLÇÜM HATASIDIR; deploy ön-kontrolü bu koşuda hiçbir şey doğrulayamaz." >&2
        echo "      Çalışma dizini: ${APP_DIR}" >&2
        sed 's/^/      | /' "${MVN_ERR}" >&2
        exit 2
    fi
    [[ "${val}" == "null"* ]] && val=""
    printf '%s' "${val}"
}

# --- SELF-CONTAINED WAR mı? Öyleyse denetlenecek bir şey yok ---
# zeus.war.packaging-excludes, ince WAR dışlama regex'idir. Tip parent'ları onu BOŞALTARAK
# self-contained WAR seçer (zeus-standalone-parent, zeus-bff-parent). O durumda uygulamanın
# runtime kapanışının tamamı WAR'ın içindedir ve descriptor com.zeus'a referans vermez —
# kapsamı paylaşımlı module'e karşı denetlemek yalnızca yanlış pozitif üretir.
#
# Kontrol PARENT ADINA değil POLİTİKA PROPERTY'sine bakar: böylece ileride eklenecek her
# izole tip otomatik kapsanır ve script'in parent adlarını bilmesi gerekmez.
PKG_EXCLUDES="$(prop_or_die zeus.war.packaging-excludes)"
if [[ -z "${PKG_EXCLUDES// /}" ]]; then
    echo ">> Self-contained WAR (zeus.war.packaging-excludes boş) — kapsam denetimi ATLANDI."
    echo "   Tüm runtime bağımlılıklar WAR içinde taşınır; com.zeus module'ü kullanılmaz."
    exit 0
fi

# App'in hedeflediği module SLOT'u (zeus.module.slot, zeus-parent'tan; üretilen
# jboss-deployment-structure.xml'e yazılan değerle aynı kaynak). Kapsam bu slot'a karşı denetlenir.
SLOT="$(prop_or_die zeus.module.slot)"
[[ -z "${SLOT// /}" ]] && SLOT="main"
MODULE_DIR="${WILDFLY_HOME}/modules/com/zeus/${SLOT}"

# Geçen / atlanan kontrollerin kaydı. Kapanış satırı BUNLARDAN üretilir; hiç koşmamış
# olabilecek ölçümleri iddia eden sabit bir "✅ hepsi tamam" satırı basılmaz
# (final review, Important 4 — yan not).
PASSED=()
SKIPPED=()

# ─────────────────────────────────────────────────────────────────────────────────────
# com.zeus.soap KULLANIMI: uygulamanın NE YAPTIĞINA bakılır, platform sabitine DEĞİL.
#
# CXF artık paylaşımlı com.zeus module'ünde (zeus-wildfly-module/pom.xml) — bir STANDART
# tip uygulama SOAP İSTEMCİSİ olarak (zeus-sms gibi) CXF kullanabilir ve bunun için HİÇBİR
# opt-in YAZMAZ; com.zeus'a zaten bağlıdır. com.zeus.soap module'ü YALNIZ bir uygulama
# gerçek bir @WebService ENDPOINT'İ YAYINLIYORSA (SOAP TİPİ, zeus-soap-parent) gerekir.
# Ölçüt bu yüzden TEK ve DOĞRUDANDIR: üretilen descriptor'ın FİİLEN com.zeus.soap'ı
# import edip etmediği (aşağıda DESC_SOAP).
#
# ESKİDEN burada AYRICA bir EXCL_SOAP izi (zeus.war.packaging-excludes içinde 'cxf-core'
# aranarak) tutulur, USES_SOAP = DESC_SOAP || EXCL_SOAP olurdu ve iki iz arasında
# "İKİ-PROPERTY TUTARLILIK KONTROLÜ" ile İKİ YÖNLÜ tutarlılık denetlenirdi — o dönemde
# CXF yalnız opt-in eden uygulamaların dışlama listesinde (…with-soap) vardı, com.zeus'a
# CXF girince STANDART listeye de 'cxf-core' eklendi. Bu artık EXCL_SOAP'ı HER uygulamada
# true yapar: USES_SOAP eski formülle her ince WAR'da 1 olur, aşağıdaki SOAP slot kontrolü
# com.zeus.soap'ın HER sunucuda kurulu olmasını ister ve her thin-WAR deploy'unu exit 2 ile
# engeller (final review, Critical 1'in CXF'in com.zeus'a taşınmasıyla YENİDEN ortaya
# çıkışı). Bu yüzden EXCL_SOAP izi ve iki-property tutarlılık kontrolü TAMAMEN KALDIRILDI;
# artık ifade edilemeyecek bir tutarsızlığı denetliyorlardı (with-soap property'si ve
# zeus.descriptor.extra.modules artık YOK). USES_SOAP tek başına DESC_SOAP'tan türer.
#
# WAR build edilmemişse DESC_SOAP OKUNAMAZ (descriptor WAR içine gömülüdür) — o durumda
# hem descriptor kontrolü hem SOAP slot kontrolü SKIPPED'e düşer, hard failure ÜRETİLMEZ.
# ─────────────────────────────────────────────────────────────────────────────────────
# Üretilen-descriptor kontrolü: WAR build edilmişse içinde framework'ün ürettiği
# jboss-deployment-structure.xml olmalı. Yoksa zeus-generated-descriptor profili devreye
# girmemiştir (tipik neden: src/main/webapp dizini yok — boşsa .gitkeep ile var edilmeli);
# böyle bir WAR WildFly'da com.zeus'u göremez ve kriptik açılış hatası verir.
WAR="$(ls -t "${APP_DIR}"/target/*.war 2>/dev/null | head -n1 || true)"
DESC_SOAP=0
if [[ -n "${WAR}" ]]; then
    DESCRIPTOR_XML="$(unzip -p "${WAR}" WEB-INF/jboss-deployment-structure.xml 2>/dev/null || true)"
    if ! grep -q 'name="com.zeus"' <<< "${DESCRIPTOR_XML}"; then
        echo "HATA: WAR'da üretilmiş jboss-deployment-structure.xml yok: $(basename "${WAR}")" >&2
        echo "      zeus-generated-descriptor profili devreye girmemiş görünüyor." >&2
        echo "      Kontrol: app'te src/main/webapp dizini var mı? (boşsa .gitkeep ile oluşturun" >&2
        echo "      — profil bu dizinin varlığıyla aktifleşir) Sonra yeniden build edin." >&2
        exit 2
    fi
    PASSED+=("üretilmiş descriptor WAR'da yerinde ($(basename "${WAR}"))")

    if grep -q 'name="com.zeus.soap"' <<< "${DESCRIPTOR_XML}"; then DESC_SOAP=1; fi
else
    SKIPPED+=("descriptor kontrolü (target/ altında WAR yok — önce 'mvn package')")
fi

# NİHAİ KARAR: uygulama com.zeus.soap'ı kullanıyor mu? Tek ölçüt DESC_SOAP'tır — WAR
# build edilmemişse bilinmez ve USES_SOAP=0 kalır (aşağıdaki SOAP slot kontrolü SKIPPED'e
# düşer, hard failure ÜRETMEZ).
USES_SOAP=0
if (( DESC_SOAP )); then USES_SOAP=1; fi

SOAP_SLOT=""
SOAP_MODULE_DIR=""
if (( USES_SOAP )); then
    # Slot değeri YALNIZ opt-in doğrulandıktan SONRA okunur. (Bu çözüm bir ara sürümde
    # eksik-bağımlılık dalıyla BİRLİKTE silinmişti; oysa spec yalnız o dalın kaldırılmasını
    # söylüyordu — kurulu olmayan bir com.zeus.soap slot'unu hedefleyen uygulama, guard'ın
    # önlemek için var olduğu kriptik deploy hatasını alıyordu.)
    SOAP_SLOT="$(prop_or_die zeus.soap.module.slot)"
    [[ -z "${SOAP_SLOT// /}" ]] && SOAP_SLOT="main"
    SOAP_MODULE_DIR="${WILDFLY_HOME}/modules/com/zeus/soap/${SOAP_SLOT}"
else
    SKIPPED+=("com.zeus.soap slot kontrolü (uygulama bu module'ü opt-in ETMİYOR)")
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
PASSED+=("com.zeus:${SLOT} slot'u sunucuda kurulu")

if [[ -n "${SOAP_MODULE_DIR}" ]]; then
    if [[ ! -f "${SOAP_MODULE_DIR}/module.xml" ]]; then
        echo "HATA: uygulamanın hedeflediği com.zeus.soap:${SOAP_SLOT} slot'u bu sunucuda kurulu değil: ${SOAP_MODULE_DIR}" >&2
        echo "      (uygulama bu module'ü OPT-IN ediyor: descriptor import'u ve/veya CXF dışlaması var)" >&2
        if [[ "${SOAP_SLOT}" == "main" ]]; then
            echo "      Önce: ( cd zeus-fw && ./scripts/install-zeus-module.sh --module soap )" >&2
        else
            echo "      Önce: ( cd zeus-fw && ./scripts/install-zeus-module.sh --module soap --slot ${SOAP_SLOT} )" >&2
            echo "      (yeni slot kurulumu WildFly restart'ı gerektirmez)" >&2
        fi
        exit 2
    fi
    PASSED+=("com.zeus.soap:${SOAP_SLOT} slot'u sunucuda kurulu (opt-in)")
fi

# ─────────────────────────────────────────────────────────────────────────────────────
# TERS KAPSAM KONTROLÜ: "sildiğimiz şey sunucuda VAR olmalı"
#
# Üretilen dışlama listesi HER ZAMAN çalışma ağacındaki zeus-wildfly-module kapanışından
# doğar; uygulamanın gerçekte bağlandığı zeus.module.slot ile arasında yapısal bir bağ
# YOKTUR. İki ulaşılabilir sapma senaryosu:
#   (a) zeus-wildfly-module'e bir bağımlılık eklenip install-zeus-module.sh yalnız
#       STAGING'e çalıştırılır → ağaçtaki listeler o jar'ı artık TÜM uygulamalar için
#       dışlar, com.zeus:main'i yenilenmemiş prod sunucusuna deploy edenler dahil;
#   (b) versiyonlu slotlarda zeus.module.slot eski bir IMMUTABLE slot'u gösterirken
#       listeler yeni kapanıştan üretilir.
# Sonuç aynı: hedeflenen slot'ta OLMAYAN bir jar WAR'dan atılır → NoClassDefFoundError.
# Spec §4'ün kabul ettiği taviz bunun TERSİYDİ (module'de olmayanın WAR'a sessizce
# konması, ki zararsızdır); bu yön kabul edilmiş bir taviz değildir (final review,
# Important 4).
#
# KRİTİK İNCELİK: sabit kuyruk girdileri (ojdbc/orai18n/ucp, jakarta.*-api, lombok,
# jarmode, gömülü tomcat) module'de BİLEREK yoktur — onlar WildFly'ın kendi
# module'lerinden gelir ya da runtime'da hiç gerekmez. Bu kontrolün dışında tutulmazlarsa
# guard HER ZAMAN kırmızı olur. Kuyruğun TEK KAYNAĞI üreticidir; buraya kopyalanmaz.
# ─────────────────────────────────────────────────────────────────────────────────────
FIXED_TAIL="$("${FW_ROOT}/scripts/generate-war-excludes.sh" --print fixed-tail)"

set +e
# ERR trap GEÇİCİ OLARAK KAPATILIR: ERR sinyali `set -e`'den BAĞIMSIZDIR (bash `set +e`
# altında da tetiklenir) — aşağıdaki python3 çağrısının rc'si (0=geçti/1=hata/3=atlandı)
# BİLEREK case ile ayrıştırılıyor; rc=3 bir HATA DEĞİL, "atlandı" demektir. Trap açık
# kalsaydı bu NORMAL atlama yolunda bile sahte bir "HATA: komut başarısız" satırı basardı.
trap - ERR
PKG_EXCLUDES="${PKG_EXCLUDES}" FIXED_TAIL="${FIXED_TAIL}" \
MODULE_DIR="${MODULE_DIR}" SOAP_MODULE_DIR="${SOAP_MODULE_DIR}" \
SLOT="${SLOT}" SOAP_SLOT="${SOAP_SLOT}" python3 - <<'PY'
import os, re, sys

rx = os.environ['PKG_EXCLUDES'].strip()
tail = os.environ['FIXED_TAIL'].strip()

# Üreticinin bastığı biçim: %regex[WEB-INF/lib/(a|b|c)-[0-9][^/]*\.jar]
m = re.match(r'^%regex\[WEB-INF/lib/\((.*)\)-\[0-9\]\[\^/\]\*\\\.jar\]$', rx)
if not m:
    # Elle yazılmış / beklenmedik biçim: denetleyecek yapılandırılmış bilgi yok.
    # ÇIKIŞ KODU 3 = "atlandı" (0 = koştu ve geçti, 1 = kırmızı) — çağıran bash bu üçünü
    # ayırt eder ki kapanış satırı koşmamış bir kontrolü GEÇTİ diye saymasın.
    print(">> UYARI: zeus.war.packaging-excludes üreticinin bastığı biçimde değil —"
          " ters kapsam kontrolü ATLANDI.")
    sys.exit(3)

tokens = m.group(1).split('|')

# Sabit kuyruk iki yoldan elenir:
#  1) token, kuyruğun bir alternatifiyle BİREBİR aynıysa (kuyruğun kendisi listenin
#     sonuna olduğu gibi eklenir: 'ojdbc[0-9]+', 'spring-boot-starter-tomcat(-runtime)?'…)
#  2) token'ın düz artifactId hâli kuyruk KALIBINA uyuyorsa (kapanıştan gelen gerçek
#     artifactId'ler: 'ojdbc17', 'tomcat-embed-el', 'jakarta.annotation-api'…)
tail_alts = set(tail.split('|'))
tail_re = re.compile('^(?:' + tail + ')$')

def plain(tok):
    return tok.replace('\\.', '.')

dirs = [d for d in (os.environ['MODULE_DIR'], os.environ.get('SOAP_MODULE_DIR', '')) if d and os.path.isdir(d)]
jars = []
for d in dirs:
    jars += [f for f in os.listdir(d) if f.endswith('.jar')]

missing = []
checked = 0
for tok in tokens:
    if not tok or tok in tail_alts:
        continue
    aid = plain(tok)
    if tail_re.match(aid):
        continue
    checked += 1
    pat = re.compile('^' + re.escape(aid) + r'-[0-9][^/]*\.jar$')
    if not any(pat.match(j) for j in jars):
        missing.append(aid)

slots = 'com.zeus:' + os.environ['SLOT']
if os.environ.get('SOAP_SLOT'):
    slots += ' ∪ com.zeus.soap:' + os.environ['SOAP_SLOT']

if missing:
    sys.stderr.write("\n❌ TERS KAPSAM HATASI: WAR'dan SİLİNEN aşağıdaki artifactId'lerin\n")
    sys.stderr.write("   hedeflenen slot'ta (%s) karşılığı YOK\n" % slots)
    sys.stderr.write("   (WildFly'da NoClassDefFoundError'a yol açar):\n")
    for a in missing:
        sys.stderr.write("     - %s\n" % a)
    sys.stderr.write("\n   Olası neden: dışlama listesi module'den DAHA YENİ — module bu sunucuda\n")
    sys.stderr.write("   henüz yenilenmedi ya da app eski bir immutable slot'u hedefliyor.\n")
    sys.stderr.write("   Çözüm: ( cd zeus-fw && ./scripts/install-zeus-module.sh )  — veya\n")
    sys.stderr.write("   app'in zeus.module.slot değerini listeyi üreten release ile hizalayın.\n")
    sys.exit(1)

print(">> Ters kapsam: %d dışlanan artifactId'nin hepsi %s slot'unda mevcut." % (checked, slots))
PY
rev_rc=$?
# ERR trap'i geri aç: geçici kapatma yalnız yukarıdaki set +e bloğuna özeldi.
trap 'rc=$?; echo "HATA: ${BASH_SOURCE[0]}:${LINENO} — komut başarısız (çıkış ${rc}): ${BASH_COMMAND}" >&2' ERR
set -e
case "${rev_rc}" in
    0) PASSED+=("ters kapsam: dışlanan her artifactId hedeflenen slot(lar)da mevcut") ;;
    3) SKIPPED+=("ters kapsam (zeus.war.packaging-excludes üreticinin biçiminde değil)") ;;
    *) exit 1 ;;
esac

# Kapanış satırı SABİT DEĞİL: yalnızca GERÇEKTEN koşan kontrolleri sayar, atlananları
# ayrıca söyler. Eskiden koşulsuz basılan "✅ Slot kurulu, üretilmiş descriptor yerinde,
# dışlanan jar'lar slot'ta mevcut." satırı, WAR yokken hiç yapılmamış iki ölçümü de
# iddia ediyordu (final review, Important 4 — yan not).
echo "✅ Deploy ön-kontrolü geçti. Doğrulanan:"
for c in "${PASSED[@]}"; do echo "   - ${c}"; done
if ((${#SKIPPED[@]})); then
    echo "   ATLANAN kontroller (bu koşuda ölçülmedi):"
    for c in "${SKIPPED[@]}"; do echo "   - ${c}"; done
fi
