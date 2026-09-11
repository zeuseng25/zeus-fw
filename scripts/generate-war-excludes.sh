#!/usr/bin/env bash
#
# WAR dışlama listesi üreteci (PLATFORM scripti).
#
# İnce WAR'ın WEB-INF/lib'inden atılacak jar'ların regex'ini, paylaşımlı WildFly
# module'lerinin BAĞIMLILIK SÖZLEŞMESİNDEN üretir. Kural: "module'ün verdiğini at,
# kalan her şeyi WAR'da taşı" (denylist). Eskiden tersiydi ("zeus- dışındakini at")
# ve module'de olmayan bağımlılıklar sessizce siliniyordu → WildFly'da
# NoClassDefFoundError. Gerekçe: gelistirmeler/19-war-paketleme-module-farkindaligi.md
#
# Kullanım:
#   ./scripts/generate-war-excludes.sh --print standard   # regex'i stdout'a bas
#   ./scripts/generate-war-excludes.sh --print soap       # com.zeus ∪ com.zeus.soap
#   ./scripts/generate-war-excludes.sh --print fixed-tail # sabit kuyruk (module dışı küme)
#   ./scripts/generate-war-excludes.sh --write            # iki parent POM'u güncelle
#   ./scripts/generate-war-excludes.sh --check            # POM'lar güncel mi (CI, SALT-OKUNUR)
#   ./scripts/generate-war-excludes.sh                    # = --check (argümansız varsayılan)
#
set -euo pipefail
# Sessiz ölüm YASAK: set -e ile düşen her komut nerede düştüğünü söylesin.
trap 'rc=$?; echo "HATA: ${BASH_SOURCE[0]}:${LINENO} — komut başarısız (çıkış ${rc}): ${BASH_COMMAND}" >&2' ERR

FW_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MVN="${MVN:-mvn}"

# İKİ KATMANLI AKIL SAĞLIĞI TABANI.
#
# 1) MUTLAK TABAN — "neredeyse boş" listeyi yakalar. Gerçek listeler 168 (standard) ve
#    193 (soap) artifactId içeriyor; eski taban 20 idi ve bu, listenin %88'ini kaybettiği
#    bir ölçüm hatasını bile YEŞİL geçirirdi. Asıl tehlikeli yön KISALMIŞ listedir: daha az
#    dışlama = module'ün ZATEN verdiği jar'lar WAR'a girer = 08-wildfly-module-dagitim.md'nin
#    ikinci-kopya LinkageError'ı. Taban orantılı hale getirildi (final review, M1).
MIN_ARTIFACT_IDS=100
#
# 2) KÜÇÜLME EŞİĞİ — mutlak taban yalnız felaketi yakalar; sessizce 168'den 120'ye düşen bir
#    liste ondan geçerdi. Bu yüzden yeni liste, POM'da HÂLİHAZIRDA COMMIT'Lİ olan sayıyla da
#    karşılaştırılır: %10'dan fazla küçülme AÇIK BİR BAYRAK olmadan reddedilir.
#    Meşru küçülme (bir bağımlılık gerçekten kaldırıldı) için: ZEUS_ALLOW_SHRINK=1 ile çalıştırın.
SHRINK_TOLERANCE_PCT=10
ZEUS_ALLOW_SHRINK="${ZEUS_ALLOW_SHRINK:-0}"

# Module'de OLMAYAN ama WAR'a da GİRMEMESİ gereken küme.
#   ojdbc/orai18n/ucp → WildFly'ın kendi com.oracle.ojdbc module'ünden gelir; WAR'daki
#     ikinci kopya JNDI Connection'ı ile tip ayrışması yaratır (ClassCastException).
#   jakarta.*-api     → WildFly server module'lerinden gelir; kopyası LinkageError üretir.
#   lombok/jarmode    → runtime'da işlevsiz, WAR'ı şişirir.
#   GÖMÜLÜ TOMCAT (tomcat-embed-*, spring-boot-tomcat, spring-boot-starter-tomcat[-runtime])
#     → konteyner WildFly/Undertow'dur; gömülü Tomcat WAR'a hiç girmemeli. Bu girdiler
#     ALLOWLIST döneminde "zeus- olmayan her şey atılır" kuralıyla ÖRTÜLÜ olarak
#     atılıyordu; polarite denylist'e çevrilince o koruma kalktı ve gömülü Tomcat ince
#     WAR'lara girmeye başladı. tomcat-embed-core, 146 adet `jakarta/servlet/**` sınıfı
#     taşır → deployment classloader'ında servlet API'sinin İKİNCİ kopyası → tam olarak
#     `jakarta.*-api` girdisinin önlemek için var olduğu LinkageError sınıfı, kural jar'ın
#     İÇİNDEKİNE değil artifactId YAZILIŞINA baktığı için yanından dolaşarak.
#     packagingExcludes yalnız WAR paketlemesini etkiler; `spring-boot:run` / `local`
#     profil (gömülü Tomcat ile lokal çalıştırma) bundan etkilenmez.
# zeus-* BU LİSTEDE YOKTUR: module'e girmez AMA WAR'da taşınır (tek istisna).
FIXED_TAIL='ojdbc[0-9]+|orai18n|ucp[0-9]+|jakarta\.[a-z.]+-api|lombok|spring-boot-jarmode-[a-z]+|tomcat-embed-[a-z-]+|spring-boot-tomcat|spring-boot-starter-tomcat(-runtime)?'

# Bir sözleşme modülünün runtime kapanışındaki artifactId'leri basar.
# install-zeus-module.sh module'ü ÜRETİRKEN aynı kaynağı (dependency, includeScope=runtime)
# kullanır — liste ile module'ün aynı kümeyi görmesi buna dayanır.
closure_artifact_ids() {
    local module_dir="$1" out err
    out="$(mktemp)"
    err="$(mktemp)"
    # stdout VE stderr AYRI yakalanır; stderr YALNIZ hata dallarında basılır — eskiden
    # yalnız stdout susturuluyordu, stderr serbestçe akıyordu ve yeşil `--check` koşusunda
    # bile mvn/JVM gürültüsü (ör. sun.misc.Unsafe uyarıları) çıktıyı kirletiyordu. Desen
    # scripts/test-com-zeus-cxf-sizintisi.sh:19-34 ile aynı. Başarısızlığı AÇIKÇA kontrol
    # ediyoruz — subshell'in dönüş kodu, fonksiyonun geri kalanındaki sed/grep/awk boru
    # hattına gizlenmesin (fix round 1, Important 1).
    if ! ( cd "${FW_ROOT}/${module_dir}" \
      && ${MVN} -q -B -Dstyle.color=never dependency:list \
           -DincludeScope=runtime -DoutputFile="${out}" ) >/dev/null 2>"${err}"; then
        echo "HATA: '${module_dir}' için 'mvn dependency:list' başarısız oldu (yukarıdaki mvn çıktısına bakın)." >&2
        cat "${err}" >&2
        rm -f "${out}" "${err}"
        return 1
    fi
    # format: groupId:artifactId:jar[:classifier]:version:scope
    sed 's/\x1b\[[0-9;]*m//g; s/^[[:space:]]*//' "${out}" \
      | grep -E '^[^:]+:[^:]+:[^:]+:' \
      | awk -F: '{print $2}' \
      | grep -Ev '^zeus-[a-z0-9-]+$' \
      | sort -u
    rm -f "${out}" "${err}"
}

# POM'da HÂLİHAZIRDA commit'li listedeki artifactId sayısı (sabit kuyruk HARİÇ).
# Dosya/property/beklenen biçim yoksa -1 ("bilinmiyor") döner — o durumda küçülme
# karşılaştırması yapılmaz, yalnız mutlak taban uygulanır.
committed_id_count() {  # $1=pom  $2=property adı
    if [[ ! -f "$1" ]]; then printf '%s\n' -1; return 0; fi
    POM="$1" PROP="$2" TAIL="${FIXED_TAIL}" python3 - <<'PY'
import io, os, re
s = io.open(os.environ['POM'], encoding='utf-8').read()
prop = os.environ['PROP']
tail = os.environ['TAIL']
m = re.search(r'<' + re.escape(prop) + r'>(.*?)</' + re.escape(prop) + r'>', s, re.S)
if not m:
    print(-1); raise SystemExit
mm = re.match(r'^%regex\[WEB-INF/lib/\((.*)\)-\[0-9\]\[\^/\]\*\\\.jar\]$', m.group(1).strip())
if not mm:
    print(-1); raise SystemExit
alts = mm.group(1)
suffix = '|' + tail
if alts.endswith(suffix):
    alts = alts[:-len(suffix)]
print(len([t for t in alts.split('|') if t]))
PY
}

# artifactId listesini (stdin) alır; boş/şüpheli derecede küçükse HATA basıp non-zero
# döner — geçerse listeyi olduğu gibi stdout'a basar. Boş bir listenin build_regex'e
# sessizce ulaşıp neredeyse-hiçbir-şeyi-dışlamayan bir regex üretmesine karşı son
# savunma hattı (fix round 1, Important 1) + belirgin KÜÇÜLMEYE karşı ikinci hat
# (final review, M1).
check_ids_sane() {  # $1=etiket (hata mesajında kullanılır)  $2=commit'li sayı (-1=bilinmiyor)
    local label="$1" committed="${2:--1}" ids n floor
    ids="$(cat)"
    n=0
    if [[ -n "${ids}" ]]; then
        n="$(grep -c . <<< "${ids}" || true)"
    fi
    if (( n < MIN_ARTIFACT_IDS )); then
        echo "HATA: '${label}' listesi şüpheli derecede küçük (${n} artifactId, mutlak taban=${MIN_ARTIFACT_IDS})." >&2
        echo "      Olası neden: 'mvn dependency:list' sessizce başarısız oldu / eksik çıktı üretti." >&2
        echo "      Yazma İPTAL edildi — POM'lar DEĞİŞTİRİLMEDİ." >&2
        return 1
    fi
    if (( committed > 0 )); then
        # Tam sayı aritmetiği: yeni liste, commit'li sayının (100-tolerans)%'inden azsa RED.
        floor=$(( committed * (100 - SHRINK_TOLERANCE_PCT) / 100 ))
        if (( n < floor && ZEUS_ALLOW_SHRINK != 1 )); then
            echo "HATA: '${label}' listesi BELİRGİN ŞEKİLDE KÜÇÜLDÜ: ${committed} → ${n} artifactId" >&2
            echo "      (kabul edilen alt sınır=${floor}, tolerans=%${SHRINK_TOLERANCE_PCT})." >&2
            echo "      Kısalmış bir dışlama listesi, module'ün ZATEN verdiği jar'ları WAR'a sokar" >&2
            echo "      → aynı sınıfın iki kopyası → LinkageError (gelistirmeler/08-wildfly-module-dagitim.md)." >&2
            echo "      Küçülme GERÇEKTEN kastediliyorsa (bağımlılık kaldırıldı):" >&2
            echo "        ZEUS_ALLOW_SHRINK=1 ./scripts/generate-war-excludes.sh --write" >&2
            return 1
        fi
    fi
    printf '%s\n' "${ids}"
}

# artifactId listesini %regex[...] ifadesine çevirir.
# -[0-9] koruması ŞART: alternation'da 'spring-boot' varken sürüm kontrolü olmadan
# 'spring-boot-custom-1.0.jar' de eşleşir ve module'de OLMAYAN bir jar yanlışlıkla atılırdı.
build_regex() {
    local ids alt
    ids="$(cat)"
    alt="$(sed 's/\./\\./g' <<< "${ids}" | paste -sd'|' -)"
    printf '%%regex[WEB-INF/lib/(%s|%s)-[0-9][^/]*\\.jar]\n' "${alt}" "${FIXED_TAIL}"
}

list_standard() {
    closure_artifact_ids zeus-wildfly-module \
      | check_ids_sane "standard" "$(committed_id_count "${STD_POM}" "zeus.war.packaging-excludes")" \
      | build_regex
}

list_soap() {
    # BİRLEŞİM: com.zeus ∪ com.zeus.soap. SOAP WAR'ına iki module'ün de içeriği girmemeli;
    # tek liste kullanılsa CXF yığını WAR'a girer ve com.zeus.soap ile çift kopya olurdu.
    # Küçülme karşılaştırmasının referansı zeus-soap-parent'taki commit'li listedir.
    { closure_artifact_ids zeus-wildfly-module
      closure_artifact_ids zeus-soap-wildfly-module; } | sort -u \
      | check_ids_sane "soap" "$(committed_id_count "${SOAP_POM}" "zeus.war.packaging-excludes")" \
      | build_regex
}

# Marker'ları hem bash guard'ında hem python tarafında AYNI ALT DİZE ile ararız (fix
# round 1, Important 2): eskiden guard 'ZEUS-WAR-EXCLUDES:BEGIN' alt dizesini grep'lerken
# python tam yorum metnini (`s.index(BEGIN_MARK)`) arıyordu — yorumun yeniden sarılması ya
# da em dash yerine ASCII tire gelmesi guard'ı geçip python'da işlenmemiş ValueError'a
# dönüşüyordu. Artık python de BEGIN/END yorum satırlarını bu kısa alt dizeyle bulup
# satırları OLDUĞU GİBİ (metnini değiştirmeden) koruyor; yalnız aradaki property satırını
# değiştiriyor.
MARK_BEGIN='ZEUS-WAR-EXCLUDES:BEGIN'
MARK_END='ZEUS-WAR-EXCLUDES:END'

# KAYNAK dosyayı okur, verilen marker bloğunun İÇİNDEKİ property satırını yeni regex ile
# değiştirir ve sonucu HEDEF dosyaya yazar. KAYNAĞA DOKUNMAZ — bu ayrım `--check`'in
# gerçekten salt-okunur olmasını sağlar (eskiden `--check` POM'ları yazıp EXIT trap'inde geri
# yüklüyordu; SIGKILL / CI job timeout trap'i çalıştırmaz ve çalışma ağacında YARIM
# YAZILMIŞ bir parent POM bırakabilirdi — final review, Important 2).
#
# MARKER YOKSA HATA (final review, Important 3): eskiden 0 dönüp stdout'a not düşüyordu,
# `--check` ise o stdout'u /dev/null'a yolluyordu → marker bir merge/elle düzenlemeyle
# kaybolduğunda `--check` "✅ uyumlu" diyordu. Tüm gerekçesi "drift yapısal olarak
# imkânsız" olan bir mekanizmada drift detektörünün kendi hata biçimi SESSİZ OLAMAZ.
# KAYNAK DOSYA YOKSA: davranış $7 ile BELİRTİLİR, varsayılan ZORUNLU'dur (final review, M2).
# Eskiden her eksik dosya sessizce atlanıyordu: `zeus-parent/pom.xml` yoksa (bozuk checkout,
# yanlış FW_ROOT, yarım merge) zincir hiç koşmuyor, `--check` diff edecek bir şey bulamıyor ve
# `✅ ... uyumlu.` basıyordu. `zeus-parent/pom.xml`'in yokluğu YAPISAL BİR HATADIR — her
# uygulamanın parent'ı odur. Atlama YALNIZ `zeus-soap-parent` için meşrudur (opsiyonel tip
# parent'ı); orada da sessiz değil, stdout'a açık bir not düşülerek.
#
# $1=kaynak dosya  $2=property adı  $3=marker-begin  $4=marker-end  $5=regex  $6=hedef dosya
# $7=eksikse-atla (1 = opsiyonel; boş/0 = ZORUNLU, yoksa HATA)
# KAYNAK == HEDEF DEĞİL: render HER ZAMAN bir tmp dosyasına yazılır, kaynak POM'a DEĞİL —
# bu ayrım `--check`'in gerçekten salt-okunur olmasını sağlar (yukarıdaki not).
render_pom() {
    local pom="$1" prop="$2" mark_begin="$3" mark_end="$4" regex="$5" out="$6" optional="${7:-0}"
    if [[ ! -f "${pom}" ]]; then
        if [[ "${optional}" == "1" ]]; then
            echo ">> atlandı (opsiyonel tip parent'ı yok): ${pom}"
            return 0
        fi
        echo "HATA: ZORUNLU kaynak POM bulunamadı: ${pom}" >&2
        echo "      Bu dosya olmadan '${prop}' listesi ÜRETİLEMEZ ve drift detektörü" >&2
        echo "      denetleyecek hiçbir şey bulamaz — 'atlandı' değil, YAPISAL HATA." >&2
        echo "      Olası neden: bozuk/eksik checkout, yanlış FW_ROOT, yarım merge." >&2
        return 1
    fi
    if ! grep -q "${mark_begin}" "${pom}"; then
        echo "HATA: '${mark_begin}' marker'ı bulunamadı: ${pom}" >&2
        echo "      Üretilen blok bir merge / elle düzenleme ile kaybolmuş olabilir." >&2
        echo "      Marker olmadan liste ÜRETİLEMEZ ve POM sessizce eski listede donar." >&2
        echo "      Çözüm: POM'a ${mark_begin}/${mark_end} yorum çiftini geri koyun." >&2
        return 1
    fi
    PROP="${prop}" MARK_BEGIN="${mark_begin}" MARK_END="${mark_end}" REGEX="${regex}" OUT="${out}" \
        python3 - "${pom}" <<'PY'
import io,os,sys
pom = sys.argv[1]
prop = os.environ['PROP']
rx = os.environ['REGEX']
mb = os.environ['MARK_BEGIN']
me = os.environ['MARK_END']
out = os.environ['OUT']
s = io.open(pom, encoding='utf-8').read()

bpos = s.find(mb)
if bpos == -1:
    sys.exit("HATA: '" + mb + "' bulunamadi: " + pom)
epos = s.find(me, bpos)
if epos == -1:
    sys.exit("HATA: '" + me + "' bulunamadi: " + pom)

begin_line_end = s.find('\n', bpos)
begin_line_end = begin_line_end + 1 if begin_line_end != -1 else len(s)

end_line_start = s.rfind('\n', 0, epos) + 1
end_line_end = s.find('\n', epos)
end_line_end = end_line_end + 1 if end_line_end != -1 else len(s)

indent = ' ' * 8
new_middle = indent + "<" + prop + ">" + rx + "</" + prop + ">\n"

s2 = s[:begin_line_end] + new_middle + s[end_line_start:end_line_end] + s[end_line_end:]
io.open(out, 'w', encoding='utf-8').write(s2)
PY
}

# Render edilmiş dosyayı hedef POM'un YERİNE ATOMİK koyar: önce aynı dizinde geçici bir
# ada kopyalanır, sonra rename(2) ile yerine geçer. Böylece POM hiçbir an YARIM yazılmış
# gözlenmez — aynı checkout'ta paralel koşan bir `mvn` bozuk POM okuyamaz.
install_pom() {  # $1=render edilmiş dosya  $2=hedef pom
    [[ -f "$1" ]] || return 0
    cp "$1" "$2.tmp"
    mv -f "$2.tmp" "$2"
    echo ">> güncellendi: $2"
}

STD_POM="${FW_ROOT}/zeus-parent/pom.xml"
SOAP_POM="${FW_ROOT}/zeus-soap-parent/pom.xml"

# Argümansız çalıştırmanın varsayılanı --check'tir (SALT-OKUNUR). Yazma niyeti her zaman
# AÇIKÇA belirtilir: modları çoğunlukla salt-okunur olan bir script'in kazara POM yazması
# istenmez. install-zeus-module.sh zaten `--write` ile açıkça çağırır.
case "${1:---check}" in
    --print)
        case "${2:-standard}" in
            standard)   list_standard ;;
            soap)       list_soap ;;
            # Sabit kuyruğun TEK KAYNAĞI burasıdır; verify-module-coverage.sh ters yönlü
            # kontrolde bu kalıpları hariç tutmak için bu modu çağırır (kopyalamaz).
            fixed-tail) printf '%s\n' "${FIXED_TAIL}" ;;
            *) echo "bilinmeyen liste: ${2}" >&2; exit 2 ;;
        esac
        ;;
    --write)
        # NOT: regex'i ÖNCE bir DEĞİŞKENE ATA, sonra render_pom'a argüman olarak geç.
        # `render_pom "$pom" "$(list_standard)"` gibi doğrudan argüman-içi komut ikamesi
        # KULLANMAYIN: `set -e` başarısız bir komut ikamesini yalnız ATAMA'nın SAĞ tarafında
        # yakalar; bir komuta ARGÜMAN olarak geçildiğinde ikame başarısız olsa bile üstteki
        # komut BOŞ argümanla çalışmaya devam eder ve script sessizce ilerler (fix round 1,
        # Important 1 — ampirik doğrulandı, bkz. task-2-report.md fix bölümü).
        regex_std="$(list_standard)" || { echo "HATA: standard listesi üretilemedi — --write İPTAL edildi." >&2; exit 1; }
        regex_soap="$(list_soap)"    || { echo "HATA: soap listesi üretilemedi — --write İPTAL edildi." >&2; exit 1; }
        tmp="$(mktemp -d)"; trap 'rm -rf "${tmp}"' EXIT
        # ÖNCE HEPSİNİ tmp'ye render et, SONRA yerine koy: aradaki bir hata (ör. marker
        # kaybı) hiçbir POM'a dokunmadan durur. Eskiden iki yazma arasındaki hata
        # zeus-parent'ı YENİ, zeus-soap-parent'ı ESKİ listede bırakıyordu (final review,
        # Important 2 — dosyalar arası atomiklik).
        render_pom "${STD_POM}" "zeus.war.packaging-excludes" \
            "${MARK_BEGIN}" "${MARK_END}" "${regex_std}" "${tmp}/std.pom" || exit 1
        # SOAP_POM opsiyonel (7. argüman=1): zeus-soap-parent bir TİP parent'ıdır, her
        # kurulumda bulunmak zorunda değildir. STD_POM ise zorunludur — varsayılan.
        render_pom "${SOAP_POM}" "zeus.war.packaging-excludes" \
            "${MARK_BEGIN}" "${MARK_END}" "${regex_soap}" "${tmp}/soap.pom" 1 || exit 1
        install_pom "${tmp}/std.pom"  "${STD_POM}"
        install_pom "${tmp}/soap.pom" "${SOAP_POM}"
        ;;
    --check)
        # SALT-OKUNUR: render tmp'ye yapılır, POM'lara HİÇ dokunulmaz (ne yazma, ne geri
        # yükleme). Bu yüzden EXIT trap'i yalnız tmp dizinini siler.
        tmp="$(mktemp -d)"; trap 'rm -rf "${tmp}"' EXIT
        regex_std="$(list_standard)" || { echo "HATA: standard listesi üretilemedi — --check İPTAL edildi." >&2; exit 1; }
        regex_soap="$(list_soap)"    || { echo "HATA: soap listesi üretilemedi — --check İPTAL edildi." >&2; exit 1; }
        # render_pom'un stdout'u ARTIK YUTULMUYOR: tek bastığı şey "opsiyonel parent yok"
        # notudur ve `--check`'in onu /dev/null'a yollaması, atlamayı görünmez kılıyordu
        # (final review, M2). STD_POM ZORUNLU'dur (7. argüman yok).
        render_pom "${STD_POM}" "zeus.war.packaging-excludes" \
            "${MARK_BEGIN}" "${MARK_END}" "${regex_std}" "${tmp}/std.pom" || exit 1
        render_pom "${SOAP_POM}" "zeus.war.packaging-excludes" \
            "${MARK_BEGIN}" "${MARK_END}" "${regex_soap}" "${tmp}/soap.pom" 1 || exit 1
        rc=0
        if [[ -f "${tmp}/std.pom" ]]; then
            diff -q "${STD_POM}" "${tmp}/std.pom" >/dev/null || rc=1
        fi
        if [[ -f "${tmp}/soap.pom" ]]; then
            diff -q "${SOAP_POM}" "${tmp}/soap.pom" >/dev/null || rc=1
        fi
        if [[ "${rc}" != 0 ]]; then
            echo "❌ Üretilmiş WAR dışlama listesi GÜNCEL DEĞİL. Çalıştırın: ./scripts/generate-war-excludes.sh --write" >&2
        else
            echo "✅ WAR dışlama listeleri module sözleşmeleriyle uyumlu."
        fi
        exit "${rc}"
        ;;
    *) echo "kullanım: $0 [--check (varsayılan)] [--write] [--print standard|soap|fixed-tail]" >&2; exit 2 ;;
esac
