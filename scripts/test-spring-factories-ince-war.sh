#!/usr/bin/env bash
# zeus-base'in META-INF/spring.factories'i, İNCE WAR'da GERÇEKTEN çalışan uzantı tipleri
# dışında bir şey kaydediyor mu?
#
# NEDEN (bu guard bir kusurdan doğdu — 2026-09-11, gerçek sunucu deneyi):
# İnce WAR modelinde spring-boot jar'ı paylaşımlı com.zeus WildFly module'ündedir ve o
# classloader WAR'ın WEB-INF/lib'indeki META-INF/spring.factories dosyalarını GÖREMEZ.
# Bu yüzden spring.factories'e yapılan bir kayıt, birim testlerinde ve gömülü çalıştırmada
# ÇALIŞIRKEN üretimde SESSİZCE HİÇ ÇALIŞMAYABİLİR. Task 3'ün güvenlik ağı
# (ZeusCapabilityVerifier, EnvironmentPostProcessor olarak kayıtlıydı) tam olarak böyle
# öldü: spring-wildfly-arch, zeus.database.enabled silinmiş hâlde sessizce deploy oldu.
# Hiçbir birim testi bunu yakalayamaz — yakalama yeri BURASIDIR.
#
# ÖLÇÜM: kayıt anahtarları dosyadan TÜRETİLİR (kopyalanmaz); her anahtar, "ince WAR'da
# yüklendiği KANITLANMIŞ" tiplerin listesiyle karşılaştırılır. Ölçüm yapılamazsa (dosya yok,
# hiç anahtar çıkarılamadı, kayıtlı sınıfın kaynağı yok) guard KIRMIZI döner — bu repo iki
# kez "hiçbir şey ölçmeden yeşil raporlayan" guard yayınladı, burada o hataya düşülmez.
#
# KAPSAM: yalnız zeus-base. zeus-logger'ın spring.factories'indeki
# CorrelationLoggingEnvironmentPostProcessor BİLİNEN ve TELAFİ EDİLMİŞ bir istisnadır:
# WildFly'da çalışmadığı biliniyor ve log pattern'ı ZeusServletInitializer enjekte ediyor
# (bkz. o sınıfın javadoc'u + gelistirmeler/18-correlation-id.md). zeus-base'de ise böyle
# bir telafi yoktur; oraya konan her kayıt çalıştığı VARSAYILIR — guard bu varsayımı korur.
#
# Kullanım:
#   ./scripts/test-spring-factories-ince-war.sh
set -uo pipefail

FW_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FACTORIES="${FW_ROOT}/zeus-base/src/main/resources/META-INF/spring.factories"
KAYNAK_KOK="${FW_ROOT}/zeus-base/src/main/java"
fail=0

echo "── test-spring-factories-ince-war.sh"

# İNCE WAR'DA YÜKLENDİĞİ KANITLANMIŞ uzantı tipleri.
# Buraya bir tip eklemek, "Spring bu tipi BEAN (deployment) classloader'ı ile yüklüyor ve
# bu GERÇEK bir WildFly deploy'unda görüldü" demektir — teoriyle değil, kanıtla genişletilir.
#
#   AutoConfigurationImportFilter
#     AutoConfigurationImportSelector#getAutoConfigurationImportFilters →
#     SpringFactoriesLoader.loadFactories(AutoConfigurationImportFilter.class, this.beanClassLoader)
#     Kanıt (server.log, 2026-09-11): hata izi
#     "deployment.spring-wildfly-arch-0.0.1-SNAPSHOT.war//com.zeus.framework.autoconfig.ZeusAutoConfigurationFilter.match"
#     — sınıf WAR'ın deployment classloader'ından yüklenmiş.
INCE_WAR_CALISAN=(
    "org.springframework.boot.autoconfigure.AutoConfigurationImportFilter"
)

# ÇALIŞMADIĞI KANITLANMIŞ tipler — yalnız DAHA İYİ HATA MESAJI için; listede olmayan her
# anahtar zaten kırmızıdır.
INCE_WAR_CALISMAYAN=(
    "org.springframework.boot.EnvironmentPostProcessor"
    "org.springframework.boot.env.EnvironmentPostProcessor"
)

[[ -f "${FACTORIES}" ]] || {
    echo "  ❌ ölçüm yapılamadı: dosya yok — ${FACTORIES}"
    echo "     (Kayıt dosyası taşındıysa bu guard'ın yolu da güncellenmeli; sessiz yeşil yasak.)"
    exit 1
}

# 1) Anahtarları TÜRET: yorumları at, satır sonu '\' ile devam eden kayıtları birleştir.
DUZ="$(awk '
    { s = $0
      sub(/[[:space:]]*#.*$/, "", s)
      if (s ~ /\\[[:space:]]*$/) { sub(/\\[[:space:]]*$/, "", s); buf = buf s; next }
      s = buf s; buf = ""
      gsub(/[[:space:]]/, "", s)
      if (s != "") print s
    }' "${FACTORIES}")"

ANAHTARLAR="$(cut -d= -f1 <<< "${DUZ}" | grep -vE '^$' | sort -u)"
if [[ -z "${ANAHTARLAR}" ]]; then
    echo "  ❌ ${FACTORIES##*/} dosyasından hiç kayıt anahtarı çıkarılamadı — ÖLÇÜM HATASI."
    echo "     (Dosya boş ya da biçimi değişmiş olabilir; guard hiçbir şey doğrulamadan yeşil DÖNMEZ.)"
    exit 1
fi
echo "  >> ${FACTORIES##*/}: $(wc -l <<< "${ANAHTARLAR}" | tr -d ' ') kayıt anahtarı, izinli tip: ${#INCE_WAR_CALISAN[@]}"

# 2) Her anahtar izin listesinde mi?
while IFS= read -r anahtar; do
    [[ -z "${anahtar}" ]] && continue
    izinli=0
    for t in "${INCE_WAR_CALISAN[@]}"; do
        [[ "${anahtar}" == "${t}" ]] && { izinli=1; break; }
    done
    if (( izinli )); then
        echo "     ✅ ${anahtar}"
        continue
    fi
    echo "     ❌ ${anahtar}"
    bilinen_kotu=0
    for t in "${INCE_WAR_CALISMAYAN[@]}"; do
        [[ "${anahtar}" == "${t}" ]] && { bilinen_kotu=1; break; }
    done
    if (( bilinen_kotu )); then
        echo "        Bu tipin ince WAR'da ÇALIŞMADIĞI gerçek deploy ile KANITLANDI: kayıt"
        echo "        sessizce yok sayılır, uygulama hatasız ama işlevsiz açılır."
    else
        echo "        Bu tipin ince WAR'da yüklendiği KANITLANMADI. spring-boot jar'ı paylaşımlı"
        echo "        com.zeus module'ündedir ve WAR'ın WEB-INF/lib'ini göremez."
    fi
    echo "        Çözüm: mantığı yüklendiği KANITLANMIŞ bir yola taşıyın —"
    echo "        AutoConfigurationImportFilter, META-INF/spring/...AutoConfiguration.imports"
    echo "        ya da ZeusServletInitializer. Yeni bir tipi izinli saymak için ÖNCE gerçek"
    echo "        sunucuda kanıtlayın, sonra bu script'teki INCE_WAR_CALISAN listesine ekleyin."
    fail=1
done <<< "${ANAHTARLAR}"

# 3) Kayıtlı sınıflar gerçekten var mı ve o arayüzü uyguluyor mu? (bayat/typolu kayıt avı)
denetlenen=0
while IFS= read -r satir; do
    [[ -z "${satir}" ]] && continue
    anahtar="${satir%%=*}"
    arayuz_kisa="${anahtar##*.}"
    degerler="${satir#*=}"
    IFS=',' read -r -a siniflar <<< "${degerler}"
    for sinif in "${siniflar[@]}"; do
        [[ -z "${sinif}" ]] && continue
        yol="${KAYNAK_KOK}/$(tr '.' '/' <<< "${sinif}").java"
        if [[ ! -f "${yol}" ]]; then
            echo "     ❌ kayıtlı sınıfın kaynağı yok: ${sinif}"
            echo "        (${yol#"${FW_ROOT}/"}) — bayat ya da typo'lu kayıt; çalışma zamanında sessizce yok sayılır."
            fail=1
            continue
        fi
        if ! tr '\n' ' ' < "${yol}" | grep -qE "implements[^{]*${arayuz_kisa}"; then
            echo "     ❌ ${sinif} kaynağı '${arayuz_kisa}' arayüzünü uygular görünmüyor"
            echo "        (kayıt anahtarı: ${anahtar})"
            fail=1
            continue
        fi
        denetlenen=$((denetlenen + 1))
    done
done <<< "${DUZ}"

if (( denetlenen == 0 )); then
    echo "  ❌ hiçbir kayıtlı sınıf DOĞRULANAMADI — ÖLÇÜM HATASI (sessiz yeşil yasak)."
    exit 1
fi

if (( fail )); then
    echo "  ❌ spring.factories ince WAR kuralını ihlal ediyor"
else
    echo "  ✅ ${denetlenen} kayıtlı sınıfın tamamı ince WAR'da çalıştığı kanıtlanmış tiplerde"
fi
exit "${fail}"
