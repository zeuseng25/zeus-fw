#!/usr/bin/env bash
#
# Paylaşımlı WildFly 'com.zeus' module'ünü kurar (PLATFORM scripti).
#
# WAR boyutunu düşürmek için tüm 3. parti kütüphaneler (Spring, Spring Boot,
# Hibernate, Jackson, ...) bu module'e konur ve hiçbir uygulamanın WAR'ına paketlenmez.
# Module TEK ve PAYLAŞIMLI'dır: 'com.zeus' module'ünü kullanan tüm uygulamalar bu module'den
# yüklenir. Bu yüzden script tek bir uygulamanın değil, MERKEZİ bağımlılık sözleşmesinin
# (zeus-wildfly-module) runtime kapanışını çözer.
#
# jakarta.*-api jar'ları module'e KONMAZ; WildFly'ın kendi server module'lerinden export edilir
# (çift sınıf / LinkageError önlemek için). lombok ve jarmode runtime'da gereksizdir.
# zeus-* (framework) jar'ları da module'e KONMAZ; bunlar uygulamanın WAR'ında (WEB-INF/lib) taşınır.
#
# Kullanım (hedef sunucu WILDFLY_HOME ile seçilir — staging/prod ayrımı):
#   ./scripts/install-zeus-module.sh                     # com.zeus, varsayılan slot: main
#   ./scripts/install-zeus-module.sh --slot 1.1.0        # versiyonlu slot (com.zeus:1.1.0)
#   ./scripts/install-zeus-module.sh --module soap       # com.zeus.soap (CXF yığını; SOAP tipi)
#   ./scripts/install-zeus-module.sh --module soap --base-slot 1.1.0   # com.zeus referans slot'u
#   WILDFLY_HOME=/path/staging-wildfly ./scripts/install-zeus-module.sh [--slot X]
#
# --module soap: zeus-soap-wildfly-module sözleşmesinin kapanışını çözer ve TEMEL com.zeus
# kapanışında ZATEN OLAN jar'ları KÜME FARKI ile atlar (çift jar / LinkageError önlenir).
# Üretilen module.xml, com.zeus'a (--base-slot) bağımlıdır.
# Jar'lar Maven'den (dependency:copy-dependencies) alınır; WAR'a ihtiyaç yoktur.
#
# SLOT'lar (bkz. gelistirmeler/10-versiyonlu-slot-uretilen-descriptor.md):
# - Versiyonlu slot'lar IMMUTABLE'dır: bir kez kurulur, ASLA üzerine yazılmaz (script reddeder;
#   bilinçli yeniden üretim için FORCE=1). Her slot bir BOM release'inin donmuş kopyasıdır.
# - YENİ slot dizini eklemek WildFly restart'ı GEREKTİRMEZ (module'ler ilk referansta yüklenir);
#   restart yalnızca YÜKLÜ bir module'ün (örn. main) içeriği değişince gerekir.
# - 'main' slot'u geriye uyumluluk için mutable bırakılmıştır (bugünkü davranış).
#
# Yeni bir uygulama, burada (zeus-wildfly-module) olmayan bir runtime kütüphanesi kullanıyorsa
# önce o bağımlılık zeus-wildfly-module/pom.xml'e eklenmeli, sonra bu script yeniden çalıştırılmalıdır.
#
# Detay: gelistirmeler/08-wildfly-module-dagitim.md
#
set -euo pipefail

WILDFLY_HOME="${WILDFLY_HOME:-/Users/omer/workspaces/intellij/wildfly-41/wildfly-41.0.0.Final}"
ZEUS_FW_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# --- Argümanlar: --slot <ad> (vars. main) · --module <base|soap> (vars. base) · --base-slot <ad> ---
SLOT="main"
MODULE_KIND="base"
BASE_SLOT="main"   # soap module.xml'inin referans verdiği com.zeus slot'u
while [[ $# -gt 0 ]]; do
    case "$1" in
        --slot)        [[ $# -ge 2 ]] || { echo "HATA: --slot bir değer ister (örn. --slot 1.1.0)" >&2; exit 2; }
                       SLOT="$2"; shift 2 ;;
        --slot=*)      SLOT="${1#--slot=}"; shift ;;
        --module)      [[ $# -ge 2 ]] || { echo "HATA: --module bir değer ister (base|soap)" >&2; exit 2; }
                       MODULE_KIND="$2"; shift 2 ;;
        --module=*)    MODULE_KIND="${1#--module=}"; shift ;;
        --base-slot)   [[ $# -ge 2 ]] || { echo "HATA: --base-slot bir değer ister" >&2; exit 2; }
                       BASE_SLOT="$2"; shift 2 ;;
        --base-slot=*) BASE_SLOT="${1#--base-slot=}"; shift ;;
        *) echo "HATA: bilinmeyen argüman: $1  (kullanım: $0 [--slot <ad>] [--module base|soap] [--base-slot <ad>])" >&2; exit 2 ;;
    esac
done
[[ "${SLOT}" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] || { echo "HATA: geçersiz slot adı: '${SLOT}'" >&2; exit 2; }

# Module türü: ad, sözleşme dizini ve WildFly module yolu
case "${MODULE_KIND}" in
    base) MODULE_NAME="com.zeus"
          MODULE_BUILD_DIR="${ZEUS_FW_DIR}/zeus-wildfly-module"
          MODULE_DIR="${WILDFLY_HOME}/modules/com/zeus/${SLOT}" ;;
    soap) MODULE_NAME="com.zeus.soap"
          MODULE_BUILD_DIR="${ZEUS_FW_DIR}/zeus-soap-wildfly-module"
          MODULE_DIR="${WILDFLY_HOME}/modules/com/zeus/soap/${SLOT}" ;;
    *) echo "HATA: geçersiz --module değeri: '${MODULE_KIND}' (base|soap)" >&2; exit 2 ;;
esac

# --- Immutability kilidi: versiyonlu slot'lar bir kez kurulur, üzerine yazılmaz ---
# (main geriye uyumluluk için mutable; versiyonlu slot = BOM release'inin donmuş kopyası.
#  Yanlış üretilmiş bir slot'u bilinçli yeniden üretmek için: FORCE=1 ... --slot X)
if [[ "${SLOT}" != "main" && -d "${MODULE_DIR}" && "${FORCE:-0}" != "1" ]]; then
    echo "HATA: com.zeus:${SLOT} slot'u zaten kurulu: ${MODULE_DIR}" >&2
    echo "      Versiyonlu slot'lar IMMUTABLE'dır (üzerine yazılmaz). Yeni içerik için yeni bir" >&2
    echo "      slot adı kullanın; bu slot'u bilinçli yeniden üretmek için FORCE=1 ile çalıştırın." >&2
    exit 3
fi

# Module'e KONMAYACAK jar'lar (WildFly server module'lerinden gelir veya gereksiz).
# zeus-* jar'ları hiçbir paylaşımlı module'e KONMAZ (WAR'da taşınırlar) → genel kalıp.
# xml.ws / xml.soap api'leri: WildFly server module'leri (SOAP kapanışında görülür).
# ojdbc/orai18n/ucp: Oracle sürücüsü WildFly'ın KENDİ com.oracle.ojdbc module'ünden gelir
#   (standalone.xml datasource'u ona bağlı). com.zeus'a da kopyalanırsa sunucuda İKİ sürücü
#   olur: JNDI Connection'ı bir classloader'ın sınıfı, uygulamanın gördüğü tip diğerininki
#   → ClassCastException/LinkageError. zeus-database ojdbc'yi compile scope'ta bildirir
#   (uygulamalar sürücüyü tekrar yazmasın diye); buradaki dışlama onun module'e sızmasını önler.
EXCLUDE_REGEX='^(jakarta\.(activation|annotation|inject|persistence|transaction|validation|xml\.bind|xml\.ws|xml\.soap)-api|lombok|spring-boot-jarmode-[a-z]+|zeus-[a-z0-9-]+|ojdbc[0-9]+|orai18n|ucp[0-9]+)-.*\.jar$'

# --- 1) Runtime bağımlılık jar'larını topla (sözleşme pom'undan) ---
# includeScope=runtime => compile+runtime; provided (tomcat) ve test hariç.
TMP="$(mktemp -d)"
trap 'rm -rf "${TMP}"' EXIT
echo ">> Runtime bağımlılıkları toplanıyor ($(basename "${MODULE_BUILD_DIR}"), dependency:copy-dependencies)..."
cd "${MODULE_BUILD_DIR}"
mvn -q dependency:copy-dependencies \
    -DincludeScope=runtime \
    -DoutputDirectory="${TMP}/lib"

# soap modunda: TEMEL com.zeus kapanışı da çözülür; orada zaten olan jar'lar KÜME FARKI ile
# atlanır (aynı sınıflar iki module'de bulunursa LinkageError riski doğar).
if [[ "${MODULE_KIND}" == "soap" ]]; then
    echo ">> Temel (com.zeus) kapanışı çözülüyor (küme farkı için)..."
    cd "${ZEUS_FW_DIR}/zeus-wildfly-module"
    mvn -q dependency:copy-dependencies \
        -DincludeScope=runtime \
        -DoutputDirectory="${TMP}/base-lib"
fi

# --- 2) Module dizinini sıfırla ve jar'ları kopyala ---
rm -rf "${MODULE_DIR}"
mkdir -p "${MODULE_DIR}"
copied=0
skipped_base=0
for jar in "${TMP}/lib"/*.jar; do
    base="$(basename "${jar}")"
    if [[ "${base}" =~ ${EXCLUDE_REGEX} ]]; then
        continue
    fi
    if [[ "${MODULE_KIND}" == "soap" && -f "${TMP}/base-lib/${base}" ]]; then
        skipped_base=$((skipped_base + 1))
        continue
    fi
    cp "${jar}" "${MODULE_DIR}/"
    copied=$((copied + 1))
done
echo ">> ${copied} jar kopyalandı -> ${MODULE_DIR}"
[[ "${MODULE_KIND}" == "soap" ]] && echo ">> ${skipped_base} jar atlandı (temel com.zeus module'ünde zaten var)"

# --- 4) Jar'lara Jandex annotation index'i göm ---
# WildFly @HandlesTypes taraması (Spring SCI -> WebApplicationInitializer) deployment'taki
# sınıfı module'deki üst hiyerarşiye bağlayabilsin diye. jboss-deployment-structure.xml'de
# com.zeus dependency'si annotations="true" ile bu index'leri import eder.
# Jandex ARAÇ olarak Maven'dan çözülür (module içeriğine bağımlı DEĞİL):
# Hibernate 7 kapanışında jandex jar'ı artık yok (hibernate-models kullanılıyor);
# indexleme aracı ~/.m2'den alınır, module'e KONMAZ.
JANDEX_VERSION="3.2.0"
JANDEX_JAR="${HOME}/.m2/repository/io/smallrye/jandex/${JANDEX_VERSION}/jandex-${JANDEX_VERSION}.jar"
if [[ ! -f "${JANDEX_JAR}" ]]; then
    mvn -q dependency:get -Dartifact="io.smallrye:jandex:${JANDEX_VERSION}" -Dtransitive=false
fi
if [[ ! -f "${JANDEX_JAR}" ]]; then
    echo "HATA: jandex ${JANDEX_VERSION} çözülemedi; annotation index gömülemiyor." >&2
    exit 1
fi
echo ">> Jandex index gömülüyor (${copied} jar)..."
for jar in "${MODULE_DIR}"/*.jar; do
    java -jar "${JANDEX_JAR}" -m "${jar}" >/dev/null 2>&1 || echo "   uyarı: index gömülemedi: $(basename "${jar}")"
done
echo ">> Jandex index tamam"

# --- 5) module.xml üret ---
{
    echo '<?xml version="1.0" encoding="UTF-8"?>'
    # Slot, module kimliğinin parçasıdır ve dizinle eşleşmelidir. module.xml şema 1.6+'da
    # 'slot' attribute'u KALDIRILDI; slot 'name' içinde iki noktayla yazılır (name="ad:slot").
    # main varsayılan slot olduğundan yalnız ad yazılır (bugünkü çıktı birebir korunur).
    if [[ "${SLOT}" == "main" ]]; then
        echo "<module name=\"${MODULE_NAME}\" xmlns=\"urn:jboss:module:1.9\">"
    else
        echo "<module name=\"${MODULE_NAME}:${SLOT}\" xmlns=\"urn:jboss:module:1.9\">"
    fi
    echo '    <resources>'
    for jar in "${MODULE_DIR}"/*.jar; do
        echo "        <resource-root path=\"$(basename "${jar}")\"/>"
    done
    echo '    </resources>'
    echo '    <dependencies>'
    echo '        <module name="java.se"/>'
    # Spring WildFly içinde classpath taraması için JBoss VFS kullanır
    echo '        <module name="org.jboss.vfs"/>'
    # Objenesis (CGLIB proxy) sun.misc.Unsafe kullanır; java.se bunu içermez
    echo '        <module name="jdk.unsupported"/>'
    if [[ "${MODULE_KIND}" == "soap" ]]; then
        # CXF, Spring/Boot sınıflarını temel module'den görür (küme farkının karşılığı).
        if [[ "${BASE_SLOT}" == "main" ]]; then
            echo '        <module name="com.zeus"/>'
        else
            echo "        <module name=\"com.zeus:${BASE_SLOT}\"/>"
        fi
        # SOAP'a özgü jakarta API'leri WildFly server module'lerinden export ile.
        for m in xml.ws xml.soap xml.bind activation servlet annotation; do
            echo "        <module name=\"jakarta.${m}.api\" export=\"true\"/>"
        done
    else
        # jakarta API'leri WildFly server module'lerinden export ile (deployment görebilsin)
        # json + json.bind: Boot 4 http-converter autoconfig'inin @ConditionalOnClass(Jsonb)
        # introspection'ı tip görünmeyince WARN üretiyor; api modülleri görünür olunca temiz.
        # websocket: module'e spring-webflux girdiğinde (zeus-ai / Spring AI reactor zinciri)
        # POST_MODULE anotasyon taraması StandardWebSocketHandlerAdapter'ı link etmeye çalışır;
        # jakarta.websocket.Endpoint görünmezse deploy NoClassDefFoundError ile DÜŞER.
        for m in servlet annotation persistence transaction validation inject xml.bind activation json json.bind websocket; do
            echo "        <module name=\"jakarta.${m}.api\" export=\"true\"/>"
        done
    fi
    echo '    </dependencies>'
    echo '</module>'
} > "${MODULE_DIR}/module.xml"

echo ">> module.xml üretildi"
echo "✅ ${MODULE_NAME}:${SLOT} module kuruldu: ${MODULE_DIR}"
if [[ "${SLOT}" == "main" ]]; then
    echo "   NOT: main slot'u güncellendi → yüklüyse WildFly RESTART gerekir (module tanımı cache'li)."
else
    echo "   NOT: yeni slot — WildFly restart GEREKMEZ. Uygulamalar zeus.module.slot=${SLOT}"
    echo "        üreten parent sürümüne geçip yeniden deploy olduklarında bu slot'a bağlanır."
fi

# --- 6) WAR dışlama listelerini AYNI kapanıştan yeniden üret ---
# Module ve liste artık TEK komuttan çıkar; bu yüzden ikisinin ayrışması (drift) yapısal
# olarak imkânsızdır ve ayrı bir senkron denetimine gerek kalmaz.
# BİLEREK SONA (✅ mesajından SONRA) konur: bu noktada module ZATEN sunucuya kuruldu —
# yukarıdaki ✅ doğru bir bilgidir ve üretici düşse bile geri alınmaz. Üretici düşerse
# kullanıcının "module kuruldu ama listeler güncellenmedi" karışık bir durumda kalmaması
# için bunu module'ün kendi başarı mesajından AYRI, açık bir HATA ile bildiriyoruz
# (generate-war-excludes.sh'ın kendi HATA çıktısına ek olarak).
# Atlamak için (ör. WAR olmayan/soap-yalnız bir kurulum akışında): ZEUS_SKIP_WAR_EXCLUDES=1
if [[ "${ZEUS_SKIP_WAR_EXCLUDES:-0}" != "1" ]]; then
    echo ">> WAR dışlama listeleri yeniden üretiliyor (zeus-parent + zeus-soap-parent)..."
    if ! "$(dirname "${BASH_SOURCE[0]}")/generate-war-excludes.sh" --write; then
        echo "HATA: ${MODULE_NAME}:${SLOT} module KURULDU ama WAR dışlama listeleri GÜNCELLENEMEDİ." >&2
        echo "      Elle çalıştırın: ./scripts/generate-war-excludes.sh --write" >&2
        exit 1
    fi
fi