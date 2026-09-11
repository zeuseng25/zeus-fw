#!/usr/bin/env bash
# REPODAKİ HER modülün META-INF/spring.factories'i, İNCE WAR'da GERÇEKTEN çalışan uzantı
# tipleri dışında bir şey kaydediyor mu?
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
# ÖLÇÜM: kayıt anahtarları HER dosyadan TÜRETİLİR (kopyalanmaz); her anahtar, "ince WAR'da
# yüklendiği KANITLANMIŞ" tiplerin listesiyle karşılaştırılır. Ölçüm yapılamazsa (hiç dosya
# bulunamadı, bir dosyadan hiç anahtar çıkarılamadı, kayıtlı sınıfın kaynağı yok) guard
# KIRMIZI döner — bu repo iki kez "hiçbir şey ölçmeden yeşil raporlayan" guard yayınladı,
# burada o hataya düşülmez.
#
# KAPSAM (Task 6, Part B #3 — genişletildi): eskiden yalnız zeus-base'e bakardı; bu, DEFECT
# CLASS'ın (spring.factories kaydı ince WAR'da sessizce inert) module-agnostik olduğu ve
# CLAUDE.md'nin "yeni modül" tarifinin bunu rutin hâle getirdiği gerçeğiyle çelişiyordu. Artık
# repo genelinde `*/src/main/resources/META-INF/spring.factories` deseniyle eşleşen HER dosya
# taranır. zeus-logger'daki CorrelationLoggingEnvironmentPostProcessor BİLİNEN ve TELAFİ
# EDİLMİŞ tek istisnadır: WildFly'da çalışmadığı biliniyor ama log pattern'ı
# ZeusServletInitializer tarafından enjekte edilerek telafi ediliyor (bkz. o sınıfın javadoc'u
# + gelistirmeler/18-correlation-id.md). Bu istisna artık BAŞKA bir dosyadaki bir YORUM
# DEĞİL, aşağıdaki ISTISNALAR dizisinde ENFORCE EDİLEN bir kayıttır: modül adı + tam sınıf
# adı BİREBİR eşleşmezse istisna uygulanmaz. Başka hiçbir modülde böyle bir telafi YOKTUR;
# oraya konan her kayıt çalıştığı VARSAYILIR — guard bu varsayımı korur.
#
# Kullanım:
#   ./scripts/test-spring-factories-ince-war.sh
set -uo pipefail

FW_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
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

# Bilinen ve TELAFİ EDİLMİŞ istisnalar — "<modül dizini>::<tam sınıf adı>::<gerekçe>".
# Bir kayıt yalnız modül adı VE sınıf adı birebir eşleştiğinde istisna sayılır; başka bir
# modülde aynı sınıf adı görünse bile (ya da aynı modülde başka bir EPP kaydedilse) eşleşmez.
# YENİ bir istisna eklemek, buraya kanıtlı bir TELAFİ gerekçesi yazmak demektir — sessizce
# susturmak YASAK.
ISTISNALAR=(
    "zeus-logger::com.zeus.framework.logger.CorrelationLoggingEnvironmentPostProcessor::ince WAR'da ÇALIŞMADIĞI BİLİNİYOR (spring-boot jar'ı paylaşımlı com.zeus module'ünde, WEB-INF/lib'i göremiyor); log pattern'ı bunun YERİNE ZeusServletInitializer tarafından SpringApplicationBuilder.properties(...) ile enjekte edilerek TELAFİ EDİLİYOR (bkz. ZeusServletInitializer javadoc'u + gelistirmeler/18-correlation-id.md)."
)

# Repo genelinde TÜM src/main/resources/META-INF/spring.factories dosyalarını TÜRET — yalnız
# zeus-base'e bakmak, aynı kusurun (EPP kaydı ince WAR'da sessizce ölür) başka bir modülde
# fark edilmeden tekrarlanmasına izin verirdi (Task 6, Part B #3).
FACTORIES_LISTESI="$(find "${FW_ROOT}" -path '*/src/main/resources/META-INF/spring.factories' -not -path '*/target/*' | sort)"
if [[ -z "${FACTORIES_LISTESI}" ]]; then
    echo "  ❌ ölçüm yapılamadı: repoda hiç spring.factories bulunamadı (desen: */src/main/resources/META-INF/spring.factories)."
    echo "     (Dosyalar taşındıysa bu guard'ın deseni de güncellenmeli; sessiz yeşil yasak.)"
    exit 1
fi
echo "  >> $(wc -l <<< "${FACTORIES_LISTESI}" | tr -d ' ') spring.factories dosyası bulundu, izinli tip: ${#INCE_WAR_CALISAN[@]}, tanımlı istisna: ${#ISTISNALAR[@]}"

denetlenen=0

while IFS= read -r FACTORIES; do
    [[ -z "${FACTORIES}" ]] && continue
    MODUL_KOK="${FACTORIES%/src/main/resources/META-INF/spring.factories}"
    MODUL_AD="$(basename "${MODUL_KOK}")"
    KAYNAK_KOK="${MODUL_KOK}/src/main/java"
    echo ""
    echo "  == ${MODUL_AD} (${FACTORIES#"${FW_ROOT}"/}) =="

    # 1) Anahtarları TÜRET: yorumları at, satır sonu '\' ile devam eden kayıtları birleştir.
    DUZ="$(awk '
        { s = $0
          sub(/[[:space:]]*#.*$/, "", s)
          if (s ~ /\\[[:space:]]*$/) { sub(/\\[[:space:]]*$/, "", s); buf = buf s; next }
          s = buf s; buf = ""
          gsub(/[[:space:]]/, "", s)
          if (s != "") print s
        }' "${FACTORIES}")"

    if [[ -z "${DUZ}" ]]; then
        echo "     ❌ dosyadan hiç kayıt anahtarı çıkarılamadı — ÖLÇÜM HATASI."
        echo "        (Dosya boş ya da biçimi değişmiş olabilir; guard hiçbir şey doğrulamadan yeşil DÖNMEZ.)"
        fail=1
        continue
    fi

    # 2) Her kayıt satırı: anahtar izin listesinde mi, değilse sınıf bazında istisna var mı,
    #    ve kayıtlı her sınıfın kaynağı var mı / doğru arayüzü uyguluyor mu (bayat/typo avı).
    while IFS= read -r satir; do
        [[ -z "${satir}" ]] && continue
        anahtar="${satir%%=*}"
        arayuz_kisa="${anahtar##*.}"
        degerler="${satir#*=}"
        if [[ -z "${degerler}" ]]; then
            # bash 3.2 (macOS varsayılanı) tuzağı: "${siniflar[@]}" set -u altında BOŞ bir
            # diziyle "unbound variable" diye ÇÖKER — guard'ın kendi ÖLÇÜM HATASI mesajı
            # yerine bash'in çökme mesajı görünürdü (Task 6, Part B #2). Boş değer listesini
            # for döngüsüne HİÇ sokmadan burada yakala.
            echo "     ❌ ${anahtar} için değer listesi boş — ÖLÇÜM HATASI."
            echo "        (Kayıt 'Anahtar=' ile bitmiş olabilir; hiçbir sınıf doğrulanamaz, sessiz yeşil yasak.)"
            fail=1
            continue
        fi

        izinli_tip=0
        for t in "${INCE_WAR_CALISAN[@]}"; do
            [[ "${anahtar}" == "${t}" ]] && { izinli_tip=1; break; }
        done

        IFS=',' read -r -a siniflar <<< "${degerler}"
        for sinif in "${siniflar[@]}"; do
            [[ -z "${sinif}" ]] && continue

            yol="${KAYNAK_KOK}/$(tr '.' '/' <<< "${sinif}").java"
            if [[ ! -f "${yol}" ]]; then
                echo "     ❌ kayıtlı sınıfın kaynağı yok: ${sinif}"
                echo "        (${yol#"${FW_ROOT}"/}) — bayat ya da typo'lu kayıt; çalışma zamanında sessizce yok sayılır."
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

            if (( izinli_tip )); then
                echo "     ✅ ${anahtar} -> ${sinif}"
                continue
            fi

            # İstisna listesinde mi? (modül adı VE sınıf adı birebir eşleşmeli)
            istisna_gerekce=""
            for e in "${ISTISNALAR[@]}"; do
                e_modul="${e%%::*}"
                e_gerikalan="${e#*::}"
                e_sinif="${e_gerikalan%%::*}"
                e_gerekce="${e_gerikalan#*::}"
                if [[ "${e_modul}" == "${MODUL_AD}" && "${e_sinif}" == "${sinif}" ]]; then
                    istisna_gerekce="${e_gerekce}"
                    break
                fi
            done
            if [[ -n "${istisna_gerekce}" ]]; then
                echo "     ⚠️  ${anahtar} -> ${sinif}: İSTİSNA (telafi edilmiş, tanımlı gerekçeyle)"
                echo "        ${istisna_gerekce}"
                continue
            fi

            echo "     ❌ ${anahtar} -> ${sinif}"
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
            echo "        (Ya da davranış GERÇEKTEN telafi ediliyorsa, gerekçesiyle ISTISNALAR'a ekleyin.)"
            fail=1
        done
    done <<< "${DUZ}"
done <<< "${FACTORIES_LISTESI}"

if (( denetlenen == 0 )); then
    echo ""
    echo "  ❌ hiçbir kayıtlı sınıf DOĞRULANAMADI — ÖLÇÜM HATASI (sessiz yeşil yasak)."
    exit 1
fi

echo ""
if (( fail )); then
    echo "  ❌ spring.factories ince WAR kuralını ihlal ediyor"
else
    echo "  ✅ ${denetlenen} kayıtlı sınıfın tamamı ince WAR'da çalışıyor ya da tanımlı istisna"
fi
exit "${fail}"
