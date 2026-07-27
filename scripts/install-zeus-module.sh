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
#   ./scripts/install-zeus-module.sh                     # varsayılan slot: main
#   ./scripts/install-zeus-module.sh --slot 1.1.0        # versiyonlu slot (com.zeus:1.1.0)
#   WILDFLY_HOME=/path/staging-wildfly ./scripts/install-zeus-module.sh [--slot X]
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
MODULE_BUILD_DIR="${ZEUS_FW_DIR}/zeus-wildfly-module"   # paylaşımlı module'ün bağımlılık sözleşmesi

# --- Argümanlar: --slot <ad> (varsayılan: main) ---
SLOT="main"
while [[ $# -gt 0 ]]; do
    case "$1" in
        --slot)   [[ $# -ge 2 ]] || { echo "HATA: --slot bir değer ister (örn. --slot 1.1.0)" >&2; exit 2; }
                  SLOT="$2"; shift 2 ;;
        --slot=*) SLOT="${1#--slot=}"; shift ;;
        *) echo "HATA: bilinmeyen argüman: $1  (kullanım: $0 [--slot <ad>])" >&2; exit 2 ;;
    esac
done
[[ "${SLOT}" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] || { echo "HATA: geçersiz slot adı: '${SLOT}'" >&2; exit 2; }
MODULE_DIR="${WILDFLY_HOME}/modules/com/zeus/${SLOT}"

# --- Immutability kilidi: versiyonlu slot'lar bir kez kurulur, üzerine yazılmaz ---
# (main geriye uyumluluk için mutable; versiyonlu slot = BOM release'inin donmuş kopyası.
#  Yanlış üretilmiş bir slot'u bilinçli yeniden üretmek için: FORCE=1 ... --slot X)
if [[ "${SLOT}" != "main" && -d "${MODULE_DIR}" && "${FORCE:-0}" != "1" ]]; then
    echo "HATA: com.zeus:${SLOT} slot'u zaten kurulu: ${MODULE_DIR}" >&2
    echo "      Versiyonlu slot'lar IMMUTABLE'dır (üzerine yazılmaz). Yeni içerik için yeni bir" >&2
    echo "      slot adı kullanın; bu slot'u bilinçli yeniden üretmek için FORCE=1 ile çalıştırın." >&2
    exit 3
fi

# Module'e KONMAYACAK jar'lar (WildFly server module'lerinden gelir veya gereksiz)
EXCLUDE_REGEX='^(jakarta\.(activation|annotation|inject|persistence|transaction|validation|xml\.bind)-api|lombok|spring-boot-jarmode-layertools|zeus-(base|logger|database|service|redis|batch))-.*\.jar$'

# --- 1) Runtime bağımlılık jar'larını topla (zeus-wildfly-module sözleşmesinden) ---
# includeScope=runtime => compile+runtime; provided (tomcat) ve test hariç.
TMP="$(mktemp -d)"
trap 'rm -rf "${TMP}"' EXIT
echo ">> Runtime bağımlılıkları toplanıyor (zeus-wildfly-module, dependency:copy-dependencies)..."
cd "${MODULE_BUILD_DIR}"
mvn -q dependency:copy-dependencies \
    -DincludeScope=runtime \
    -DoutputDirectory="${TMP}/lib"

# --- 2) Module dizinini sıfırla ve jar'ları kopyala ---
rm -rf "${MODULE_DIR}"
mkdir -p "${MODULE_DIR}"
copied=0
for jar in "${TMP}/lib"/*.jar; do
    base="$(basename "${jar}")"
    if [[ "${base}" =~ ${EXCLUDE_REGEX} ]]; then
        continue
    fi
    cp "${jar}" "${MODULE_DIR}/"
    copied=$((copied + 1))
done
echo ">> ${copied} jar kopyalandı -> ${MODULE_DIR}"

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
        echo '<module name="com.zeus" xmlns="urn:jboss:module:1.9">'
    else
        echo "<module name=\"com.zeus:${SLOT}\" xmlns=\"urn:jboss:module:1.9\">"
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
    # jakarta API'leri WildFly server module'lerinden export ile (deployment görebilsin)
    # json + json.bind: Boot 4 http-converter autoconfig'inin @ConditionalOnClass(Jsonb)
    # introspection'ı tip görünmeyince WARN üretiyor; api modülleri görünür olunca temiz.
    for m in servlet annotation persistence transaction validation inject xml.bind activation json json.bind; do
        echo "        <module name=\"jakarta.${m}.api\" export=\"true\"/>"
    done
    echo '    </dependencies>'
    echo '</module>'
} > "${MODULE_DIR}/module.xml"

echo ">> module.xml üretildi"
echo "✅ com.zeus:${SLOT} module kuruldu: ${MODULE_DIR}"
if [[ "${SLOT}" == "main" ]]; then
    echo "   NOT: main slot'u güncellendi → yüklüyse WildFly RESTART gerekir (module tanımı cache'li)."
else
    echo "   NOT: yeni slot — WildFly restart GEREKMEZ. Uygulamalar zeus.module.slot=${SLOT}"
    echo "        üreten parent sürümüne geçip yeniden deploy olduklarında bu slot'a bağlanır."
fi