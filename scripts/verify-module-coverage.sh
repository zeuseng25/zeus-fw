#!/usr/bin/env bash
#
# Deploy ön-kontrolü (PLATFORM scripti).
#
# İKİ ŞEYİ denetler:
#   1) Uygulamanın hedeflediği com.zeus slot'u sunucuda KURULU mu?
#   2) WAR'da framework'ün ÜRETTİĞİ jboss-deployment-structure.xml var mı?
#
# NOT: "bağımlılık module'de var mı?" kontrolü KALDIRILDI. Denylist paketlemesinden sonra
# module'de olmayan bağımlılık WAR'da taşınır (gelistirmeler/19-war-paketleme-module-
# farkindaligi.md); onu eksik saymak yanlış pozitiftir.
#
# Kullanım:
#   ./scripts/verify-module-coverage.sh [app-dizini]   (varsayılan: cwd)
#   WILDFLY_HOME=/path/staging ./scripts/verify-module-coverage.sh /path/app
#
set -euo pipefail

WILDFLY_HOME="${WILDFLY_HOME:-/Users/omer/workspaces/intellij/wildfly-41/wildfly-41.0.0.Final}"
APP_DIR="${1:-$(pwd)}"

cd "${APP_DIR}"
MVN="mvn"

# --- SELF-CONTAINED WAR mı? Öyleyse denetlenecek bir şey yok ---
# zeus.war.packaging-excludes, ince WAR dışlama regex'idir. Tip parent'ları onu BOŞALTARAK
# self-contained WAR seçer (zeus-standalone-parent, zeus-bff-parent). O durumda uygulamanın
# runtime kapanışının tamamı WAR'ın içindedir ve descriptor com.zeus'a referans vermez —
# kapsamı paylaşımlı module'e karşı denetlemek yalnızca yanlış pozitif üretir.
#
# Kontrol PARENT ADINA değil POLİTİKA PROPERTY'sine bakar: böylece ileride eklenecek her
# izole tip otomatik kapsanır ve script'in parent adlarını bilmesi gerekmez.
PKG_EXCLUDES="$(${MVN} -q -Dstyle.color=never help:evaluate -Dexpression=zeus.war.packaging-excludes -DforceStdout 2>/dev/null || true)"
[[ "${PKG_EXCLUDES}" == "null"* ]] && PKG_EXCLUDES=""
if [[ -z "${PKG_EXCLUDES// /}" ]]; then
    echo ">> Self-contained WAR (zeus.war.packaging-excludes boş) — kapsam denetimi ATLANDI."
    echo "   Tüm runtime bağımlılıklar WAR içinde taşınır; com.zeus module'ü kullanılmaz."
    exit 0
fi

# App'in hedeflediği module SLOT'u (zeus.module.slot, zeus-parent'tan; üretilen
# jboss-deployment-structure.xml'e yazılan değerle aynı kaynak). Kapsam bu slot'a karşı denetlenir.
SLOT="$(${MVN} -q -Dstyle.color=never help:evaluate -Dexpression=zeus.module.slot -DforceStdout 2>/dev/null || true)"
[[ -z "${SLOT}" || "${SLOT}" == "null"* ]] && SLOT="main"
MODULE_DIR="${WILDFLY_HOME}/modules/com/zeus/${SLOT}"

# Üretilen-descriptor kontrolü: WAR build edilmişse içinde framework'ün ürettiği
# jboss-deployment-structure.xml olmalı. Yoksa zeus-generated-descriptor profili devreye
# girmemiştir (tipik neden: src/main/webapp dizini yok — boşsa .gitkeep ile var edilmeli);
# böyle bir WAR WildFly'da com.zeus'u göremez ve kriptik açılış hatası verir.
WAR="$(ls -t "${APP_DIR}"/target/*.war 2>/dev/null | head -n1 || true)"
if [[ -n "${WAR}" ]]; then
    if ! unzip -p "${WAR}" WEB-INF/jboss-deployment-structure.xml 2>/dev/null | grep -q 'name="com.zeus"'; then
        echo "HATA: WAR'da üretilmiş jboss-deployment-structure.xml yok: $(basename "${WAR}")" >&2
        echo "      zeus-generated-descriptor profili devreye girmemiş görünüyor." >&2
        echo "      Kontrol: app'te src/main/webapp dizini var mı? (boşsa .gitkeep ile oluşturun" >&2
        echo "      — profil bu dizinin varlığıyla aktifleşir) Sonra yeniden build edin." >&2
        exit 2
    fi
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

echo "✅ Slot kurulu ve üretilmiş descriptor yerinde."