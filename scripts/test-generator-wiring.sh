#!/usr/bin/env bash
# install-zeus-module.sh üreteci DOĞRU (mutlak, cd'lerden bağımsız) yoldan çağırıyor mu,
# ve listeler güncel mi?
#
# Not: install-zeus-module.sh script'i ÇALIŞTIRILMAZ (sunucuya yazar) — bu yüzden çağrı
# satırı yapısal olarak (regex ile) denetlenir, davranışsal olarak değil.
set -uo pipefail
FW_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
INSTALL_SCRIPT="${FW_ROOT}/scripts/install-zeus-module.sh"
fail=0

# 1) Çağrı satırı ${ZEUS_FW_DIR} ile ÇAPALANMIŞ (mutlak yol) olmalı.
grep -qE '"\$\{ZEUS_FW_DIR\}/scripts/generate-war-excludes\.sh" --write' "${INSTALL_SCRIPT}" \
  && echo "  ✅ çağrı satırı \${ZEUS_FW_DIR} ile çapalanmış" \
  || { echo "  ❌ çağrı satırı \${ZEUS_FW_DIR} ile çapalanmamış"; fail=1; }

# 2) Çağrı satırı BASH_SOURCE tabanlı göreli çözüm KULLANMAMALI — script içinde 'cd' var
#    (:100, soap için :109), bu yüzden $(dirname "${BASH_SOURCE[0]}") çağrı anında artık
#    yanlış dizine göre çözülür. Bu kalıp yeniden ortaya çıkarsa test KIRMIZI dönmeli.
grep -qE 'dirname "\$\{BASH_SOURCE\[0\]\}"\)/generate-war-excludes\.sh' "${INSTALL_SCRIPT}" \
  && { echo "  ❌ çağrı satırı hâlâ BASH_SOURCE ile göreli çözüm kullanıyor (cd sonrası bozulur)"; fail=1; } \
  || echo "  ✅ çağrı satırı BASH_SOURCE tabanlı göreli çözüm kullanmıyor"

# 3) Çağrılan script gerçekten var ve çalıştırılabilir olmalı (ZEUS_FW_DIR'ın çözdüğü yolda).
if [[ -x "${FW_ROOT}/scripts/generate-war-excludes.sh" ]]; then
    echo "  ✅ generate-war-excludes.sh var ve çalıştırılabilir"
else
    echo "  ❌ generate-war-excludes.sh yok veya çalıştırılabilir değil"
    fail=1
fi

# 4) Listeler (module kapanışı ile) hâlihazırda güncel mi?
"${FW_ROOT}/scripts/generate-war-excludes.sh" --check \
  && echo "  ✅ listeler güncel" || { echo "  ❌ listeler güncel değil"; fail=1; }

exit ${fail}
