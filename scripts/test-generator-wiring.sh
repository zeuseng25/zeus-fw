#!/usr/bin/env bash
# install-zeus-module.sh üreteci çağırıyor mu, ve listeler güncel mi?
set -uo pipefail
FW_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
fail=0
grep -q "generate-war-excludes.sh" "${FW_ROOT}/scripts/install-zeus-module.sh" \
  && echo "  ✅ install-zeus-module.sh üreteci çağırıyor" \
  || { echo "  ❌ install-zeus-module.sh üreteci çağırmıyor"; fail=1; }
"${FW_ROOT}/scripts/generate-war-excludes.sh" --check \
  && echo "  ✅ listeler güncel" || { echo "  ❌ listeler güncel değil"; fail=1; }
exit ${fail}
