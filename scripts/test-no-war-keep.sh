#!/usr/bin/env bash
# zeus.war.keep tamamen kaldırıldı mı?
set -uo pipefail
FW_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# YALNIZ KOD taranır (*.xml, *.sh). Dokümanlar property'nin KALDIRILDIĞINI anlatmak için
# adını anmaya devam eder — bu kalıntı değildir.
# kendi dosyasını hariç tutar: bu script "zeus.war.keep" adını, aradığı KALINTI olarak değil,
# ne aradığını anlatmak için anar — dokümanlardaki aynı gerekçeyle kalıntı sayılmaz.
hits="$(grep -rn "zeus\.war\.keep" "${FW_ROOT}" \
        --include='*.xml' --include='*.sh' \
        2>/dev/null | grep -v '/target/' | grep -v '\.flattened-pom\.xml' \
        | grep -v '/scripts/test-no-war-keep\.sh:' || true)"
if [[ -z "${hits}" ]]; then echo "✅ zeus.war.keep kalıntısı yok"; exit 0; fi
echo "❌ kalıntı var:"; echo "${hits}"; exit 1
