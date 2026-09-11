#!/usr/bin/env bash
#
# KURULU MODULE ↔ ÜRETİLEN WAR DIŞLAMA LİSTESİ — İKİ YÖNLÜ KÜME EŞİTLİĞİ.
#
# Tüm ince-WAR mimarisinin dayandığı tek değişmez şudur: bir jar YA paylaşımlı WildFly
# module'ündedir YA da WAR'ın WEB-INF/lib'indedir — ASLA İKİSİ BİRDEN, ASLA HİÇBİRİ.
# İki yönün de kendi arıza biçimi vardır ve ikisi de sunucuda kriptik patlar:
#
#   A \ B  (module'de VAR, dışlama listesinde YOK)
#       → jar WAR'a da paketlenir → aynı sınıfın İKİ kopyası (module + deployment
#         classloader) → LinkageError / ClassCastException.
#   B \ A  (listede VAR, module'de YOK)
#       → jar WAR'dan SİLİNİR ama sunucuda onu sağlayan hiçbir şey yoktur
#         → NoClassDefFoundError.
#
# install-zeus-module.sh module'ü kurduktan SONRA listeleri AYNI kapanıştan üretir; bu
# yüzden "drift yapısal olarak imkânsız" denir. Bu guard o iddiayı ÖLÇER, çünkü iddia
# birden çok yoldan çürüyebilir: module bir sunucuya kurulup ağaçtaki liste başka bir
# kapanıştan üretilmiş olabilir (ör. yalnız staging'e kurulum), module dizini elle
# oynanmış olabilir, ya da bir sonraki kurulum unutulmuş olabilir. Ölçülen şey ÇALIŞMA
# AĞACI DEĞİL, SUNUCUDA GERÇEKTEN DURAN dizindir.
#
# MUAF KÜME (sabit kuyruk): ojdbc/orai18n/ucp, jakarta.*-api, lombok, jarmode, gömülü
# tomcat. Bunlar WAR'dan BİLEREK atılır ama module'e de KONMAZ — WildFly'ın KENDİ server
# module'lerinden gelirler (ya da runtime'da hiç gerekmezler). Kuyruğun TEK KAYNAĞI
# üreticidir (`generate-war-excludes.sh --print fixed-tail`); buraya KOPYALANMAZ.
#
# SESSİZ YEŞİL YASAK: mvn düşerse, üretilen liste boşsa ya da module dizininde hiç jar
# yoksa bu bir ÖLÇÜM HATASIDIR ve guard KIRMIZI döner — "ölçemedim" asla "sorun yok"
# diye raporlanmaz.
#
# Kullanım:
#   ./scripts/test-module-liste-esitligi.sh
#   WILDFLY_HOME=/path/staging SLOT=1.1.0 ./scripts/test-module-liste-esitligi.sh
#
set -uo pipefail

FW_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WILDFLY_HOME="${WILDFLY_HOME:-/Users/omer/workspaces/intellij/wildfly-41/wildfly-41.0.0.Final}"
SLOT="${SLOT:-main}"
SOAP_SLOT="${SOAP_SLOT:-main}"
GEN="${FW_ROOT}/scripts/generate-war-excludes.sh"

MODULE_DIR="${WILDFLY_HOME}/modules/com/zeus/${SLOT}"
SOAP_MODULE_DIR="${WILDFLY_HOME}/modules/com/zeus/soap/${SOAP_SLOT}"

fail=0

# --- 0) Sabit kuyruk: üreticiden OKUNUR (tek kaynak, burada yeniden yazılmaz) ---
if ! FIXED_TAIL="$("${GEN}" --print fixed-tail)"; then
    echo "  ❌ ölçüm yapılamadı: sabit kuyruk okunamadı (generate-war-excludes.sh --print fixed-tail düştü)"
    exit 1
fi
if [[ -z "${FIXED_TAIL// /}" ]]; then
    echo "  ❌ ölçüm yapılamadı: sabit kuyruk BOŞ döndü (üreticinin FIXED_TAIL'i değişmiş olabilir)"
    exit 1
fi
echo "  ✅ sabit kuyruk üreticiden okundu (muaf küme)"

# --- Bir (liste, module dizinleri) çiftini iki yönlü karşılaştırır ---
# $1 = etiket · $2 = üreteç liste modu (standard|soap) · $3.. = module dizinleri
compare_pair() {
    local label="$1" mode="$2"; shift 2
    local dirs=("$@") d regex

    for d in "${dirs[@]}"; do
        if [[ ! -f "${d}/module.xml" ]]; then
            echo "  ❌ [${label}] ölçüm yapılamadı: module KURULU DEĞİL (module.xml yok): ${d}"
            echo "     Önce: ( cd zeus-fw && ./scripts/install-zeus-module.sh )"
            echo "     ve:   ( cd zeus-fw && ./scripts/install-zeus-module.sh --module soap )"
            fail=1
            return 1
        fi
    done

    # Üretilen liste (mvn KOŞAR). Çıkış kodu ve boşluk AÇIKÇA denetlenir: üreteç
    # 'check_ids_sane' ile zaten taban/küçülme denetimi yapar, ama onun HATA yolu
    # stderr'e yazıp non-zero döner — burada yutulmamalı.
    if ! regex="$("${GEN}" --print "${mode}")"; then
        echo "  ❌ [${label}] ölçüm yapılamadı: 'generate-war-excludes.sh --print ${mode}' başarısız"
        echo "     (mvn dependency:list düşmüş ya da liste akıl sağlığı tabanının altına inmiş olabilir)"
        fail=1
        return 1
    fi
    if [[ -z "${regex// /}" ]]; then
        echo "  ❌ [${label}] ölçüm yapılamadı: üretilen dışlama listesi BOŞ"
        fail=1
        return 1
    fi

    LABEL="${label}" REGEX="${regex}" TAIL="${FIXED_TAIL}" DIRS="$(printf '%s\n' "${dirs[@]}")" \
        python3 - <<'PY'
import os, re, sys

label = os.environ['LABEL']
rx    = os.environ['REGEX'].strip()
tail  = os.environ['TAIL'].strip()
dirs  = [d for d in os.environ['DIRS'].splitlines() if d]

# Üreticinin bastığı biçim: %regex[WEB-INF/lib/(a|b|c)-[0-9][^/]*\.jar]
m = re.match(r'^%regex\[WEB-INF/lib/\((.*)\)-\[0-9\]\[\^/\]\*\\\.jar\]$', rx)
if not m:
    print("  ❌ [%s] ölçüm yapılamadı: üretilen liste beklenen %%regex[...] biçiminde değil" % label)
    print("     alınan: %s" % rx[:160])
    sys.exit(1)

tokens = [t for t in m.group(1).split('|') if t]

# B tarafı: sabit kuyruk MUAF (module'de bilerek yoktur).
tail_alts = set(tail.split('|'))
tail_re   = re.compile('^(?:' + tail + ')$')
def plain(tok):
    return tok.replace('\\.', '.')
b_ids = []
for tok in tokens:
    if tok in tail_alts:
        continue
    aid = plain(tok)
    if tail_re.match(aid):
        continue
    b_ids.append(aid)

# A tarafı: SUNUCUDA gerçekten duran jar dosyaları.
jars = []
for d in dirs:
    jars += [f for f in os.listdir(d) if f.endswith('.jar')]

# ÖLÇÜM HATASI KAPILARI — boş küme sessizce YEŞİL geçemez.
if not b_ids:
    print("  ❌ [%s] ölçüm yapılamadı: listede sabit kuyruk DIŞINDA hiç artifactId yok" % label)
    sys.exit(1)
if not jars:
    print("  ❌ [%s] ölçüm yapılamadı: module dizin(ler)inde HİÇ jar yok: %s" % (label, ', '.join(dirs)))
    print("     (küme farkıyla boşalan com.zeus.soap bu guard'da TEK BAŞINA ölçülmez;")
    print("      SOAP çifti com.zeus ∪ com.zeus.soap BİRLEŞİMİ üzerinden ölçülür)")
    sys.exit(1)

# ── A \ B: module'de VAR ama dışlama listesi onu YAKALAMIYOR ────────────────────────
# Ölçüt, artifactId tahmini DEĞİL, listenin KENDİ regex'idir: WAR paketleyici de aynı
# ifadeyi uygular, yani burada eşleşmeyen jar WAR'a GERÇEKTEN girer.
full_re = re.compile('^(?:' + m.group(1) + r')-[0-9][^/]*\.jar$')
a_minus_b = sorted(j for j in jars if not full_re.match(j))

# ── B \ A: listede VAR ama module'de KARŞILIĞI YOK ──────────────────────────────────
b_minus_a = []
for aid in b_ids:
    pat = re.compile('^' + re.escape(aid) + r'-[0-9][^/]*\.jar$')
    if not any(pat.match(j) for j in jars):
        b_minus_a.append(aid)

rc = 0
if a_minus_b:
    rc = 1
    print("  ❌ [%s] A \\ B — module'de VAR ama dışlama listesi YAKALAMIYOR (%d):" % (label, len(a_minus_b)))
    for j in a_minus_b:
        print("       - %s" % j)
    print("     → bu jar'lar WAR'ın WEB-INF/lib'ine DE girer: aynı sınıfın iki kopyası")
    print("       (module + deployment classloader) → LinkageError / ClassCastException.")
    print("     Çözüm: ./scripts/generate-war-excludes.sh --write  (liste module'den GERİ kalmış)")

if b_minus_a:
    rc = 1
    print("  ❌ [%s] B \\ A — listede VAR ama module'de KARŞILIĞI YOK (%d):" % (label, len(b_minus_a)))
    for a in b_minus_a:
        print("       - %s" % a)
    print("     → bu jar'lar WAR'dan SİLİNİR ama sunucuda onları sağlayan hiçbir şey yok")
    print("       → NoClassDefFoundError.")
    print("     Çözüm: ./scripts/install-zeus-module.sh  (module listeden GERİ kalmış)")

if rc == 0:
    print("  ✅ [%s] iki yönlü eşitlik: %d module jar'ı ∧ %d dışlanan artifactId — fark YOK"
          % (label, len(jars), len(b_ids)))
sys.exit(rc)
PY
    local prc=$?
    (( prc != 0 )) && fail=1
    return 0
}

echo "  >> WILDFLY_HOME : ${WILDFLY_HOME}"
echo "  >> com.zeus     : ${MODULE_DIR}"
echo "  >> com.zeus.soap: ${SOAP_MODULE_DIR}"

# STANDART tip: yalnız com.zeus ↔ zeus-parent listesi.
compare_pair "standard" "standard" "${MODULE_DIR}"

# SOAP tipi: com.zeus ∪ com.zeus.soap ↔ zeus-soap-parent listesi (üreteç de BİRLEŞİM
# üretir). com.zeus.soap küme farkıyla BOŞALABİLİR (CXF com.zeus'a taşındı) — bu MEŞRU
# ve birleşim ölçümü bundan etkilenmez.
compare_pair "soap" "soap" "${MODULE_DIR}" "${SOAP_MODULE_DIR}"

if (( fail )); then
    echo "  ❌ module ↔ liste eşitliği BOZUK (yukarıdaki yönlere bakın)"
else
    echo "  ✅ module ↔ liste iki yönlü eşitliği SAĞLANIYOR (standard + soap)"
fi
exit ${fail}
