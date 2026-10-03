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
# Sessiz ölüm YASAK: set -e ile düşen her komut nerede düştüğünü söylesin.
trap 'rc=$?; echo "HATA: ${BASH_SOURCE[0]}:${LINENO} — komut başarısız (çıkış ${rc}): ${BASH_COMMAND}" >&2' ERR

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
# xml.ws / xml.soap api'leri: WildFly server module'leri (CXF artık TEMEL com.zeus
#   kapanışında da olduğu için bu iki jar hem temel hem SOAP kapanışında görülür —
#   module.xml'in HER İKİ dalı da (aşağıda) bu iki server module'ünü export etmek
#   ZORUNDADIR, yoksa CXF init'i NoClassDefFoundError ile düşer).
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
if ! mvn -q dependency:copy-dependencies \
        -DincludeScope=runtime \
        -DoutputDirectory="${TMP}/lib"; then
    echo "HATA: '${MODULE_BUILD_DIR}' için 'mvn dependency:copy-dependencies' başarısız oldu." >&2
    echo "      Ne yapılmaya çalışılıyordu: ${MODULE_NAME} module'ünün runtime bağımlılık kapanışı" >&2
    echo "      (${TMP}/lib altına) toplanıyordu — module bu kapanıştan üretilir." >&2
    echo "      Olası nedenler: Maven repository'ye erişilemiyor (ağ/offline), bozuk/eksik pom.xml," >&2
    echo "      yanlış JAVA_HOME (export JAVA_HOME=.../openjdk@25), veya framework henüz" >&2
    echo "      'mvn clean install' ile ~/.m2'ye kurulmamış (zeus-* jar'ları çözülemiyor)." >&2
    exit 1
fi

# soap modunda: TEMEL com.zeus kapanışı da çözülür; orada zaten olan jar'lar KÜME FARKI ile
# atlanır (aynı sınıflar iki module'de bulunursa LinkageError riski doğar).
if [[ "${MODULE_KIND}" == "soap" ]]; then
    echo ">> Temel (com.zeus) kapanışı çözülüyor (küme farkı için)..."
    cd "${ZEUS_FW_DIR}/zeus-wildfly-module"
    if ! mvn -q dependency:copy-dependencies \
            -DincludeScope=runtime \
            -DoutputDirectory="${TMP}/base-lib"; then
        echo "HATA: 'zeus-wildfly-module' için TEMEL (com.zeus) kapanışı çözülemedi (mvn dependency:copy-dependencies)." >&2
        echo "      Ne yapılmaya çalışılıyordu: --module soap kurulumunda küme farkı hesaplamak için" >&2
        echo "      com.zeus'un KENDİ runtime kapanışı ayrıca (${TMP}/base-lib altına) toplanıyordu." >&2
        echo "      Olası nedenler: Maven repository'ye erişilemiyor (ağ/offline), bozuk/eksik pom.xml," >&2
        echo "      yanlış JAVA_HOME, veya zeus-wildfly-module henüz ~/.m2'ye kurulmamış." >&2
        exit 1
    fi
fi

# GLOB GÜVENLİĞİ (nullglob) — KÜME FARKI BOŞ ÇIKABİLDİĞİ İÇİN ŞART.
# Bash varsayılanında EŞLEŞMEYEN bir glob KENDİ METNİYLE genişler: boş bir dizinde
# `for jar in "${MODULE_DIR}"/*.jar` tek turda `.../*.jar` LİTERAL'ini verir. CXF temel
# com.zeus kapanışına taşındıktan sonra com.zeus.soap'ın küme farkı ∅'dir (0 jar) ve bu
# tam olarak gerçekleşti: module.xml'e SAHTE bir `<resource-root path="*.jar"/>` satırı
# yazıldı. WildFly böyle bir module'ü yüklemez ("resource root ... not found") → SOAP
# tipi uygulamaların HEPSİ deploy'da düşerdi. nullglob eşleşmeyen glob'u SIFIR öğeye
# genişletir; aşağıdaki üç döngü (kopyalama, jandex, resource-root) artık boş dizini
# doğru şekilde "hiç tur" olarak işler.
shopt -s nullglob

# --- 2) Module dizinini sıfırla ve jar'ları kopyala ---
rm -rf "${MODULE_DIR}"
mkdir -p "${MODULE_DIR}"
copied=0
skipped_base=0
# HAM kapanış boşsa bu bir ÖLÇÜM HATASIDIR: mvn "başarılı" döndü ama hiçbir şey
# kopyalamadı. nullglob açıkken bu durum sessizce 0 jar'lık bir module üretirdi; burada
# AÇIKÇA duruyoruz. (soap'ta KÜME FARKI sonucu 0 jar MEŞRUDUR — denetlenen fark değil,
# dependency:copy-dependencies'in ürettiği HAM kapanıştır.)
src_jars=( "${TMP}/lib"/*.jar )
if (( ${#src_jars[@]} == 0 )); then
    echo "HATA: '${MODULE_BUILD_DIR}' runtime kapanışı BOŞ — ${TMP}/lib altında hiç jar yok." >&2
    echo "      'mvn dependency:copy-dependencies' başarı döndürdü ama hiçbir jar kopyalamadı;" >&2
    echo "      bu bir ÖLÇÜM HATASIDIR — module ÜRETİLMEDİ, sunucuya dokunulmadı." >&2
    exit 1
fi
for jar in "${src_jars[@]}"; do
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
    if ! mvn -q dependency:get -Dartifact="io.smallrye:jandex:${JANDEX_VERSION}" -Dtransitive=false; then
        echo "HATA: jandex ${JANDEX_VERSION} 'mvn dependency:get' ile çözülemedi." >&2
        echo "      Ne yapılmaya çalışılıyordu: annotation index aracı (jandex) ~/.m2'ye indiriliyordu" >&2
        echo "      (module İÇERİĞİNE bağımlı DEĞİLDİR, yalnızca bir ARAÇ olarak kullanılır)." >&2
        echo "      Olası nedenler: Maven repository'ye (Maven Central) erişilemiyor (ağ/offline)," >&2
        echo "      yanlış JAVA_HOME, veya io.smallrye:jandex:${JANDEX_VERSION} artık mevcut değil" >&2
        echo "      (sürüm burada güncellenmeli: ${BASH_SOURCE[0]})." >&2
        exit 1
    fi
fi
if [[ ! -f "${JANDEX_JAR}" ]]; then
    echo "HATA: jandex ${JANDEX_VERSION} 'mvn dependency:get' başarıyla döndü ama jar beklenen yolda yok: ${JANDEX_JAR}" >&2
    echo "      Olası neden: JANDEX_VERSION ile ~/.m2 repository düzeni beklenenden farklı." >&2
    exit 1
fi
echo ">> Jandex index gömülüyor (${copied} jar)..."
# Sessiz "uyarı basıp devam et" YASAK — bu, 4b'de düzeltilen OOM'un TAM AYNI ailesinden bir
# arızadır. HER jar için embed başarısız olursa module'de HİÇ jandex.idx kalmaz → 4b'nin
# önlediği geri düşüş (calculateModuleIndex) devreye girer → ${copied} jar'ın TAMAMI ham
# indexlenir → OutOfMemoryError. BAZI jar'lar için başarısız olursa daha sinsi bir arıza
# oluşur: hızlı yol (ModuleIndexBuilder) yine de çalışır ama TAMAMLANMAMIŞ bir kompozit index
# üretir — build KIRMIZI vermez, deploy KIRMIZI vermez, @HandlesTypes taraması (Spring SCI ->
# WebApplicationInitializer) o jar'lardaki sınıfları SESSİZCE KAÇIRIR. Bu yüzden ilk embed
# hatasında HARD FAILURE (yalnız-sonda-doğrula değil): eksik/yarım index'li bir module hiç
# kurulmasın, sunucuya dokunulmasın.
#
# SADECE ÇIKIŞ KODU YETMEZ — ÖLÇÜLDÜ: jandex ARACININ KENDİSİ, geçersiz/bozuk bir jar'a "-m"
# ile embed denendiğinde stacktrace basıp yine de EXIT 0 ile çıkabiliyor (scratch'te
# doğrulandı: bozuk bir jar'a karşı `java -jar jandex-3.2.0.jar -m` "zip END header not
# found" stacktrace'i basıp `$?`'yi 0 bırakıyor). Bu yüzden `$?` denetimi TEK BAŞINA
# YETERSİZDİR; her embed'den SONRA jar'ın GERÇEKTEN META-INF/jandex.idx içerdiği `jar tf`
# ile AYRICA doğrulanır — bu ikinci kontrol, bu ailedeki arızayı gerçekten yakalayan taraftır.
for jar in "${MODULE_DIR}"/*.jar; do
    jandex_rc=0
    if ! jandex_err="$(java -jar "${JANDEX_JAR}" -m "${jar}" 2>&1 >/dev/null)"; then
        jandex_rc=$?
    fi
    if (( jandex_rc != 0 )) || ! jar tf "${jar}" 2>/dev/null | grep -qx 'META-INF/jandex.idx'; then
        echo "HATA: jandex index gömülemedi/doğrulanamadı: $(basename "${jar}")" >&2
        echo "      Ne yapılmaya çalışılıyordu: '${JANDEX_JAR} -m' ile jar'ın İÇİNE" >&2
        echo "      META-INF/jandex.idx gömülüyordu (WildFly'ın hızlı-yol annotation index" >&2
        echo "      taraması [ModuleIndexBuilder] bunu okur), sonra 'jar tf' ile jar'ın İÇİNDE" >&2
        echo "      o dosyanın FİİLEN var olduğu doğrulanıyordu." >&2
        echo "      Sessizce atlanırsa (eski davranış) TÜM jar'larda başarısız olursa module'de" >&2
        echo "      HİÇ jandex.idx kalmaz ve WildFly geri düşüşe (calculateModuleIndex) düşer ->" >&2
        echo "      ${copied} jar'ın TAMAMI ham indexlenir -> OutOfMemoryError (bkz. 4b adımı)." >&2
        echo "      BAZI jar'larda başarısız olursa hızlı yol TAMAMLANMAMIŞ bir kompozit index" >&2
        echo "      üretir ve @HandlesTypes taraması o jar'lardaki sınıfları SESSİZCE KAÇIRIR." >&2
        echo "      araç çıkış kodu: ${jandex_rc}  (0 olması BAŞARI ANLAMINA GELMEZ — bkz. yukarıdaki" >&2
        echo "      ölçülmüş not; asıl kanıt jar İÇİNDE META-INF/jandex.idx'in var olmasıdır)." >&2
        echo "      java çıktısı: ${jandex_err:-<boş>}" >&2
        echo "      Olası nedenler: bozuk/kilitli jar dosyası, disk dolu, java/jandex sürüm uyuşmazlığı." >&2
        exit 1
    fi
done
echo ">> Jandex index tamam (${copied} jar'ın tamamına gömüldü, her biri doğrulandı)"

# --- 4b) JAR'SIZ MODULE: BOŞ AMA GEÇERLİ BİR ANNOTATION INDEX ŞART ---
#
# WildFly bir static module'ün annotation index'ini İKİ yoldan kurar
# (org.jboss.as.server.deployment.annotation.AnnotationIndexSupport#indexModule):
#   1) HIZLI YOL — ModuleIndexBuilder.buildCompositeIndex: module classloader'ından
#      META-INF/jandex.idx kaynaklarını okur (yukarıda her jar'a gömdüğümüz index'ler).
#   2) GERİ DÜŞÜŞ — HİÇ jandex.idx bulunamazsa calculateModuleIndex: module'ün
#      ERİŞEBİLDİĞİ TÜM .class kaynaklarını (bağımlı olduğu com.zeus'un jar'ları DAHİL)
#      TEK bir Jandex Indexer'da HAM olarak indexler.
#
# CXF, com.zeus sözleşmesine taşındıktan sonra com.zeus.soap KÜME FARKIYLA BOŞALDI
# (0 jar). Jar yoksa gömülecek index de yok → module'de HİÇ jandex.idx bulunmuyor →
# WildFly (2) yoluna düşüyor ve com.zeus'un 180 jar'ının tamamını ham indexliyor.
# Varsayılan 512m heap'te bu OutOfMemoryError ile patlıyor ve SOAP tipi HER deploy
# PARSE fazında düşüyor (ampirik olarak doğrulandı — bkz. task-3 raporu):
#   Failed to process phase PARSE ... Caused by: java.lang.OutOfMemoryError: Java heap space
#     at org.jboss.jandex.Indexer.index(...)
#     at ...AnnotationIndexSupport.calculateModuleIndex(AnnotationIndexSupport.java:124)
#
# ÇÖZÜM: jar'sız module'e BOŞ ama geçerli bir jandex index'i koyup module.xml'de dizin
# tipi bir resource-root ile tanıt. Böylece hızlı yol (1) devreye girer ve module'ün
# GERÇEK içeriği (hiçbir sınıf) doğru biçimde ifade edilmiş olur. Bir JAR kullanılmaz:
# module dizinindeki her jar WAR dışlama listesinde de karşılığı olması gereken bir
# artifact'tır (bkz. scripts/test-module-liste-esitligi.sh) — sentetik bir jar o
# eşitliği bozardı.
EMPTY_INDEX_DIR="empty-index"
if (( copied == 0 )); then
    echo ">> Module jar'sız (küme farkı ∅) — boş annotation index üretiliyor (${EMPTY_INDEX_DIR}/META-INF/jandex.idx)..."
    mkdir -p "${MODULE_DIR}/${EMPTY_INDEX_DIR}/META-INF"
    mkdir -p "${TMP}/empty-src"
    if ! java -jar "${JANDEX_JAR}" -o "${MODULE_DIR}/${EMPTY_INDEX_DIR}/META-INF/jandex.idx" \
            "${TMP}/empty-src" >/dev/null 2>&1; then
        echo "HATA: jar'sız ${MODULE_NAME} module'ü için BOŞ jandex index üretilemedi." >&2
        echo "      Bu index olmadan WildFly module'ün annotation index'ini ham tarayarak" >&2
        echo "      kurmaya çalışır (bağımlı com.zeus jar'ları dahil) ve deploy OOM ile düşer." >&2
        exit 1
    fi
    if [[ ! -s "${MODULE_DIR}/${EMPTY_INDEX_DIR}/META-INF/jandex.idx" ]]; then
        echo "HATA: boş jandex index üretildi ama dosya boş/yok: ${MODULE_DIR}/${EMPTY_INDEX_DIR}/META-INF/jandex.idx" >&2
        exit 1
    fi
fi

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
    # Jar'sız module: yukarıda (4b) üretilen boş annotation index'i DİZİN tipi bir
    # resource-root olarak tanıt — WildFly'ın hızlı index yolu bunu bulmak zorunda.
    if (( copied == 0 )); then
        echo "        <resource-root path=\"${EMPTY_INDEX_DIR}\"/>"
    fi
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
        # xml.ws + xml.soap: CXF (cxf-spring-boot-starter-jaxws) artık BURADA, TEMEL com.zeus
        # kapanışında (zeus-wildfly-module/pom.xml) — soap-özel değil. jakarta.xml.ws-api ve
        # jakarta.xml.soap-api jar'ları hem yukarıdaki EXCLUDE_REGEX'te hem WAR dışlama listesinde
        # (generate-war-excludes.sh FIXED_TAIL: 'jakarta.[a-z.]+-api') STRIPlenir — ne module'e
        # ne WAR'a konurlar. Bu iki server module'ü eksik olursa CXF init'i sırasında
        # NoClassDefFoundError ile düşer (hem standart tip CXF istemcisi hem SOAP tipi
        # uygulamalar için — com.zeus.soap import'u bu API'leri DEPLOYMENT classloader'ına
        # export eder, com.zeus'un KENDİSİNE değil). Unutulmaması guard'la denetlenir:
        # scripts/test-com-zeus-jakarta-api-kapsama.sh.
        for m in servlet annotation persistence transaction validation inject xml.bind activation json json.bind websocket xml.ws xml.soap; do
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
    if ! "${ZEUS_FW_DIR}/scripts/generate-war-excludes.sh" --write; then
        echo "HATA: ${MODULE_NAME}:${SLOT} module KURULDU ama WAR dışlama listeleri GÜNCELLENEMEDİ." >&2
        echo "      Elle çalıştırın: ./scripts/generate-war-excludes.sh --write" >&2
        exit 1
    fi
fi
