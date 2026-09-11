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
# tomcat. Bunlar B tarafında (dışlanan artifactId sayımı) muaf tutulur — ÇOĞU WAR'dan
# BİLEREK atılır VE module'e de KONMAZ (WildFly'ın KENDİ server module'lerinden gelirler
# ya da runtime'da hiç gerekmezler). İKİ İSTİSNA VAR (ölçüldü): `jakarta.mail-api` ve
# `tomcat-embed-el` FİİLEN module'e de GİRER (EXCLUDE_REGEX'in dar jakarta listesi mail'i
# kapsamaz; gömülü Tomcat'in yalnız `-el` alt-jar'ı module kapanışına sızar) — bu ikisi B
# sayımından (muafiyet nedeniyle) düşse de A tarafında (sunucudaki gerçek jar dosyaları)
# hâlâ ölçülür ve eşleşmeleri A \ B kontrolünden geçer (tam regex'in İÇİNDE hâlâ yer
# alırlar). Kuyruğun TEK KAYNAĞI üreticidir (`generate-war-excludes.sh --print
# fixed-tail`); buraya KOPYALANMAZ.
#
# KAPSAM SINIRI: com.zeus.soap TEK BAŞINA hiç ölçülmez, yalnız com.zeus ∪ com.zeus.soap
# BİRLEŞİMİ içinde (SOAP çifti). Sonuç: bir jar HER İKİ module'de BİRDEN dursa (tam da
# install-zeus-module.sh'ın --module soap küme-farkı adımının önlemesi gereken çift kopya)
# bu guard bunu YAKALAMAZ — birleşimde jar bir kez sayılır, iki module'e dağılmış olması
# fark etmez. Çift kopyanın kendisi (aynı jar'ın iki module dizininde fiziksel olarak
# durması) ayrı bir denetim gerektirir; bu guard yalnız module(ler) ↔ liste eşitliğini
# ölçer, module'ler arası ayrıklığı ölçmez.
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
# Buradaki "module.xml yok" dalı YALNIZ ZORUNLU module'ler içindir (com.zeus). OPSİYONEL
# olan com.zeus.soap çağrı yerinde elenir (aşağıya bakın) — kurulu olmayan opsiyonel bir
# module KIRMIZI değil ATLANDI'dır.
compare_pair() {
    local label="$1" mode="$2"; shift 2
    local dirs=("$@") d regex

    for d in "${dirs[@]}"; do
        if [[ ! -f "${d}/module.xml" ]]; then
            echo "  ❌ [${label}] ölçüm yapılamadı: module KURULU DEĞİL (module.xml yok): ${d}"
            echo "     Önce: ( cd zeus-fw && ./scripts/install-zeus-module.sh )"
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
    # Sınırsız çıktı büyük drift'te (ör. yanlış WILDFLY_HOME/SLOT — tüm liste "eksik"
    # görünür) yüzlerce satır basar (ölçüldü: sentetik bir koşuda 172 artifactId iki kez
    # basılmıştı). Terminali/raporu boğmadan yine de teşhis edilebilir kalması için ilk
    # ~20 ile sınırlanır; kalan sayı ayrıca bildirilir.
    CAP = 20
    for a in b_minus_a[:CAP]:
        print("       - %s" % a)
    if len(b_minus_a) > CAP:
        print("       … ve %d tane daha" % (len(b_minus_a) - CAP))
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
#
# com.zeus.soap KURULU DEĞİLSE: bu KIRMIZI DEĞİL, ATLANDI'dır (final review, Important 2).
# com.zeus.soap YALNIZ gerçek @WebService ENDPOINT'İ yayınlayan SOAP TİPİ uygulamalar için
# gerekir; yalnız REST uygulaması barındıran bir sunucunun onu kurması için hiçbir neden
# yoktur (üstelik module bugün BOŞ — 0 jar). Eski davranış böyle bir sunucuda süiti
# KALICI OLARAK KIRMIZI yapıyor, üstelik "eşitlik BOZUK" diyerek EKSİK bir OPSİYONEL
# module'ü BOZULMUŞ bir değişmez gibi teşhis ediyordu. Aynı öncül, verify-module-coverage.sh'ta
# "final review Critical 1" olarak zaten bir kez kaldırılmıştı (bkz. o dosyadaki :100-108
# gerekçesi) — buraya geri sokulmaz. Operatöre "kırmızı normaldir" öğretmek, tüm güvenlik
# hikâyesi "bir guard kırmızıya döner" olan bir sistemi çürütür.
# KIRMIZI, VAR OLAN ama listeyle UYUŞMAYAN bir soap module'üne saklıdır.
soap_skipped=0
if [[ -f "${SOAP_MODULE_DIR}/module.xml" ]]; then
    compare_pair "soap" "soap" "${MODULE_DIR}" "${SOAP_MODULE_DIR}"
else
    soap_skipped=1
    echo "  ── ATLANDI [soap]  (opsiyonel module kurulu değil: ${SOAP_MODULE_DIR})"
    echo "     Neden meşru: com.zeus.soap YALNIZ SOAP TİPİ (gerçek @WebService endpoint'i"
    echo "     yayınlayan) uygulamalar için gerekir; REST-only bir sunucuda kurulu olmaması"
    echo "     BEKLENEN durumdur. Bu koşuda soap çifti HİÇBİR ŞEY DOĞRULAMADI."
    echo "     Kurmak için: ( cd zeus-fw && ./scripts/install-zeus-module.sh --module soap )"
fi

if (( fail )); then
    echo "  ❌ module ↔ liste eşitliği BOZUK (yukarıdaki yönlere bakın)"
elif (( soap_skipped )); then
    echo "  ✅ module ↔ liste iki yönlü eşitliği SAĞLANIYOR (standard) — soap çifti ATLANDI"
else
    echo "  ✅ module ↔ liste iki yönlü eşitliği SAĞLANIYOR (standard + soap)"
fi
exit ${fail}
