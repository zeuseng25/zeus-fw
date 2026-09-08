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
#   ./scripts/generate-war-excludes.sh --write            # iki parent POM'u güncelle
#   ./scripts/generate-war-excludes.sh --check            # POM'lar güncel mi (CI)
#
set -euo pipefail

FW_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MVN="${MVN:-mvn}"

# "Neredeyse boş" bir listeyi yakalamak için akıl-sağlığı tabanı (gerçek liste ~150+
# artifactId içerir). `mvn dependency:list` sessizce başarısız olur/eksik çıktı verirse
# bu taban, WAR'a giren her şeyin silinip com.zeus module'üyle çift kopya oluşmasını
# (ya da tam tersi — her şeyin WAR'a dolmasını) önceden yakalar; bkz. fix round 1, Important 1.
MIN_ARTIFACT_IDS=20

# Module'de OLMAYAN ama WAR'a da GİRMEMESİ gereken küme.
# = install-zeus-module.sh'ın EXCLUDE_REGEX'i EKSİ zeus-* :
#   ojdbc/orai18n/ucp → WildFly'ın kendi com.oracle.ojdbc module'ünden gelir; WAR'daki
#     ikinci kopya JNDI Connection'ı ile tip ayrışması yaratır (ClassCastException).
#   jakarta.*-api     → WildFly server module'lerinden gelir; kopyası LinkageError üretir.
#   lombok/jarmode    → runtime'da işlevsiz, WAR'ı şişirir.
# zeus-* BU LİSTEDE YOKTUR: module'e girmez AMA WAR'da taşınır (tek istisna).
FIXED_TAIL='ojdbc[0-9]+|orai18n|ucp[0-9]+|jakarta\.[a-z.]+-api|lombok|spring-boot-jarmode-[a-z]+'

# Bir sözleşme modülünün runtime kapanışındaki artifactId'leri basar.
# install-zeus-module.sh module'ü ÜRETİRKEN aynı kaynağı (dependency, includeScope=runtime)
# kullanır — liste ile module'ün aynı kümeyi görmesi buna dayanır.
closure_artifact_ids() {
    local module_dir="$1" out
    out="$(mktemp)"
    # NOT: yalnız stdout /dev/null'a gidiyor — stderr AKIYOR. Eskiden `2>&1` ile ikisi
    # birden yutuluyordu; `mvn dependency:list` başarısız olduğunda teşhissiz kalıyordu
    # (fix round 1, Important 1). Başarısızlığı da AÇIKÇA kontrol ediyoruz — subshell'in
    # dönüş kodu, fonksiyonun geri kalanındaki sed/grep/awk boru hattına gizlenmesin.
    if ! ( cd "${FW_ROOT}/${module_dir}" \
      && ${MVN} -q -B -Dstyle.color=never dependency:list \
           -DincludeScope=runtime -DoutputFile="${out}" ) >/dev/null; then
        echo "HATA: '${module_dir}' için 'mvn dependency:list' başarısız oldu (yukarıdaki mvn çıktısına bakın)." >&2
        rm -f "${out}"
        return 1
    fi
    # format: groupId:artifactId:jar[:classifier]:version:scope
    sed 's/\x1b\[[0-9;]*m//g; s/^[[:space:]]*//' "${out}" \
      | grep -E '^[^:]+:[^:]+:[^:]+:' \
      | awk -F: '{print $2}' \
      | grep -Ev '^zeus-[a-z0-9-]+$' \
      | sort -u
    rm -f "${out}"
}

# artifactId listesini (stdin) alır; boş/şüpheli derecede küçükse HATA basıp non-zero
# döner — geçerse listeyi olduğu gibi stdout'a basar. Boş bir listenin build_regex'e
# sessizce ulaşıp neredeyse-hiçbir-şeyi-dışlamayan bir regex üretmesine karşı son
# savunma hattı (fix round 1, Important 1).
check_ids_sane() {  # $1=etiket (hata mesajında kullanılır)
    local label="$1" ids n
    ids="$(cat)"
    n=0
    if [[ -n "${ids}" ]]; then
        n="$(grep -c . <<< "${ids}" || true)"
    fi
    if (( n < MIN_ARTIFACT_IDS )); then
        echo "HATA: '${label}' listesi şüpheli derecede küçük (${n} artifactId, taban=${MIN_ARTIFACT_IDS})." >&2
        echo "      Olası neden: 'mvn dependency:list' sessizce başarısız oldu / eksik çıktı üretti." >&2
        echo "      Yazma İPTAL edildi — POM'lar DEĞİŞTİRİLMEDİ." >&2
        return 1
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

list_standard() { closure_artifact_ids zeus-wildfly-module | check_ids_sane "standard" | build_regex; }

list_soap() {
    # BİRLEŞİM: com.zeus ∪ com.zeus.soap. SOAP WAR'ına iki module'ün de içeriği girmemeli;
    # tek liste kullanılsa CXF yığını WAR'a girer ve com.zeus.soap ile çift kopya olurdu.
    { closure_artifact_ids zeus-wildfly-module
      closure_artifact_ids zeus-soap-wildfly-module; } | sort -u | check_ids_sane "soap" | build_regex
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

# POM'daki marker bloğunun İÇERİĞİNİ (BEGIN/END yorum satırları arasındaki property
# satırını) yeni regex ile değiştirir. BEGIN/END yorum satırlarının kendisine dokunmaz.
write_pom() {  # $1=pom yolu  $2=regex
    local pom="$1" regex="$2"
    [[ -f "${pom}" ]] || { echo ">> atlandı (yok): ${pom}"; return 0; }
    grep -q "${MARK_BEGIN}" "${pom}" || { echo ">> atlandı (marker yok): ${pom}"; return 0; }
    MARK_BEGIN="${MARK_BEGIN}" MARK_END="${MARK_END}" REGEX="${regex}" python3 - "${pom}" <<'PY'
import io,os,sys
pom = sys.argv[1]
rx = os.environ['REGEX']
mb = os.environ['MARK_BEGIN']
me = os.environ['MARK_END']
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
new_middle = indent + "<zeus.war.packaging-excludes>" + rx + "</zeus.war.packaging-excludes>\n"

s2 = s[:begin_line_end] + new_middle + s[end_line_start:end_line_end] + s[end_line_end:]
io.open(pom, 'w', encoding='utf-8').write(s2)
PY
    echo ">> güncellendi: ${pom}"
}

case "${1:---write}" in
    --print)
        case "${2:-standard}" in
            standard) list_standard ;;
            soap)     list_soap ;;
            *) echo "bilinmeyen liste: ${2}" >&2; exit 2 ;;
        esac
        ;;
    --write)
        # NOT: regex'i ÖNCE bir DEĞİŞKENE ATA, sonra write_pom'a argüman olarak geç.
        # `write_pom "$pom" "$(list_standard)"` gibi doğrudan argüman-içi komut ikamesi
        # KULLANMAYIN: `set -e` başarısız bir komut ikamesini yalnız ATAMA'nın SAĞ tarafında
        # yakalar; bir komuta ARGÜMAN olarak geçildiğinde ikame başarısız olsa bile üstteki
        # komut BOŞ argümanla çalışmaya devam eder ve script sessizce ilerler (fix round 1,
        # Important 1 — ampirik doğrulandı, bkz. task-2-report.md fix bölümü).
        regex_std="$(list_standard)" || { echo "HATA: standard listesi üretilemedi — --write İPTAL edildi." >&2; exit 1; }
        regex_soap="$(list_soap)"    || { echo "HATA: soap listesi üretilemedi — --write İPTAL edildi." >&2; exit 1; }
        write_pom "${FW_ROOT}/zeus-parent/pom.xml"      "${regex_std}"
        write_pom "${FW_ROOT}/zeus-soap-parent/pom.xml" "${regex_soap}"
        ;;
    --check)
        tmp="$(mktemp -d)"
        std_pom="${FW_ROOT}/zeus-parent/pom.xml"
        soap_pom="${FW_ROOT}/zeus-soap-parent/pom.xml"
        cp "${std_pom}" "${tmp}/std.bak"
        cp "${soap_pom}" "${tmp}/soap.bak" 2>/dev/null || true
        # POM'ları HER ÇIKIŞ YOLUNDA (başarı / hata / SIGINT) eski hâline döndür — EXIT
        # trap'i içinde, iki write_pom çağrısı arasında bir hata/kesinti olsa BİLE çalışır
        # (fix round 1, Important 2 — eskiden geri yükleme düz kod olarak `write_pom`'lardan
        # SONRA duruyordu; aradaki herhangi bir sıfır-olmayan çıkış POM'u değiştirilmiş bırakırdı).
        # NOT: burada da `[[ ... ]] && cmd` KULLANMAYIN — trap içindeyken bile `set -e`
        # geri yüklemeyi yarım bırakabilir; `if` ile açıkça dallandırıyoruz.
        restore_check_poms() {
            if [[ -f "${tmp}/std.bak" ]]; then
                cp "${tmp}/std.bak" "${std_pom}"
            fi
            if [[ -f "${tmp}/soap.bak" ]]; then
                cp "${tmp}/soap.bak" "${soap_pom}"
            fi
            rm -rf "${tmp}"
        }
        trap restore_check_poms EXIT

        regex_std="$(list_standard)" || { echo "HATA: standard listesi üretilemedi — --check İPTAL edildi." >&2; exit 1; }
        regex_soap="$(list_soap)"    || { echo "HATA: soap listesi üretilemedi — --check İPTAL edildi." >&2; exit 1; }
        write_pom "${std_pom}"  "${regex_std}"  >/dev/null
        write_pom "${soap_pom}" "${regex_soap}" >/dev/null
        rc=0
        diff -q "${tmp}/std.bak" "${std_pom}" >/dev/null || rc=1
        if [[ -f "${tmp}/soap.bak" ]]; then
            diff -q "${tmp}/soap.bak" "${soap_pom}" >/dev/null || rc=1
        fi
        if [[ "${rc}" != 0 ]]; then
            echo "❌ Üretilmiş WAR dışlama listesi GÜNCEL DEĞİL. Çalıştırın: ./scripts/generate-war-excludes.sh --write" >&2
        else
            echo "✅ WAR dışlama listeleri module sözleşmeleriyle uyumlu."
        fi
        exit "${rc}"
        ;;
    *) echo "kullanım: $0 [--print standard|soap] [--write] [--check]" >&2; exit 2 ;;
esac
