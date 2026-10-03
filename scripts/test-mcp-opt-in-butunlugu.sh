#!/usr/bin/env bash
#
# MCP opt-in BÜTÜNLÜĞÜ — R1'in build tarafındaki TEK savunması.
#
# YAKALADIĞI ARIZA (çalışma zamanında yakalanamaz):
#   Bir uygulama 'zeus.ai.mcp.enabled=true' yazar ama pom'una 'zeus-ai-mcp' bağımlılığını
#   EKLEMEZ. ZeusCapabilityVerifier bunu göremez — işaretçi sınıf (ZeusMcpAutoConfiguration)
#   WAR'da olmadığı için Class.forName başarısız olur ve verifier SESSİZ kalır. Ama yetenek
#   property'si 'true' olduğu için ZeusAutoConfigurationFilter paylaşımlı com.zeus module'ündeki
#   Spring AI MCP autoconfig'lerini VETO ETMEZ ve McpSchema her uygulamaya görünür durumdadır:
#   sonuç, Spring AI'ın ham MCP sunucusunun '/mcp' yolunda AYAĞA KALKMASI — bizim token
#   filtremiz, audit'imiz ve tool kaydımız OLMADAN. Yani kimlik denetimsiz bir ağ ucu.
#
#   Runtime çözümü YOKTUR: sorunu tespit edecek modül, eksik olan modülün kendisidir. Bu
#   asimetri işaretçi-sınıf probe'unun doğasında; o yüzden denetim BUILD tarafında, fail-closed.
#
# AYRICA denetlenen (hepsi zeus-ai-mcp'nin açılış doğrulamalarıyla aynı hizada):
#   - spring.ai.mcp.server.protocol=STREAMABLE yazılmış mı? Yazılmazsa Spring AI kullanım dışı
#     SSE taşımasını yayınlar (ölçüldü) ve SSE'nin ucu AYRI bir property'den geldiği için zeus
#     token filtresi onu KORUMAZ.
#   - spring.ai.mcp.server.streamable-http.mcp-endpoint yazılmış mı? Filtre korunacak yolu
#     bu değerden türetir; varsayılan '/mcp'dir ve açıkça bildirilmelidir.
#   - zeus.ai.mcp.token DÜZ METİN değil mi? Sır repoya yazılmaz; ${...} yer tutucusu olmalı.
#
# "Sessiz yeşil yoktur": ölçüm yapılamazsa (pom okunamadı vb.) KIRMIZI döner, atlamaz.
#
# Detay: gelistirmeler/23-zeus-ai-mcp.md
set -uo pipefail
FW_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
fail=0
say() { if (( $1 == 0 )); then echo "  ✅ $2"; else echo "  ❌ $2"; fail=1; fi; }

# Denetlenecek uygulamalar: zeus-fw'ın kardeşleri (kanıt uygulaması + örnekler).
shopt -s nullglob
APPS=()
for d in "${FW_ROOT}"/../*/; do
    [[ -f "${d}pom.xml" ]] || continue
    [[ "$(basename "${d}")" == "zeus-fw" ]] && continue
    APPS+=("${d%/}")
done

if (( ${#APPS[@]} == 0 )); then
    echo "  ATLANDI: zeus-fw'ın yanında denetlenecek uygulama reposu yok"
    exit 0
fi

denetlenen=0
for app in "${APPS[@]}"; do
    ad="$(basename "${app}")"
    # Tüm property dosyalarında (main + test) yeteneğin AÇIK bildirildiği satırı ara.
    props=()
    for f in "${app}"/src/main/resources/application*.properties \
             "${app}"/src/test/resources/application*.properties; do
        props+=("${f}")
    done
    (( ${#props[@]} == 0 )) && continue

    if ! grep -qE '^[[:space:]]*zeus\.ai\.mcp\.enabled[[:space:]]*=[[:space:]]*true' "${props[@]}" 2>/dev/null; then
        continue   # yetenek açık değil → bu uygulama bu denetimin konusu değil
    fi
    denetlenen=$((denetlenen + 1))
    echo "── ${ad}: zeus.ai.mcp.enabled=true bildirilmiş"

    # 1) ASIL İDDİA — bağımlılık pom'da var mı?
    if [[ ! -r "${app}/pom.xml" ]]; then
        say 1 "${ad}: pom.xml okunamadı (ölçüm yapılamadı — sessiz yeşil YOK)"
        continue
    fi
    if grep -qE '<artifactId>[[:space:]]*zeus-ai-mcp[[:space:]]*</artifactId>' "${app}/pom.xml"; then
        say 0 "${ad}: pom'da zeus-ai-mcp bağımlılığı VAR"
    else
        say 1 "${ad}: 'zeus.ai.mcp.enabled=true' yazılmış ama pom'da zeus-ai-mcp YOK —
        korumasız bir MCP ucu yayına girer (bkz. bu script'in başındaki açıklama).
        Ya bağımlılığı ekleyin, ya property'yi kaldırın/false yapın."
    fi

    # 2) Taşıma açıkça bildirilmiş mi?
    if grep -qE '^[[:space:]]*spring\.ai\.mcp\.server\.protocol[[:space:]]*=[[:space:]]*STREAMABLE' "${props[@]}" 2>/dev/null; then
        say 0 "${ad}: protocol=STREAMABLE bildirilmiş"
    else
        say 1 "${ad}: spring.ai.mcp.server.protocol=STREAMABLE YOK — yazılmazsa deprecated SSE
        taşıması yayınlanır ve token filtresi onu korumaz"
    fi

    if grep -qE '^[[:space:]]*spring\.ai\.mcp\.server\.streamable-http\.mcp-endpoint[[:space:]]*=' "${props[@]}" 2>/dev/null; then
        say 0 "${ad}: mcp-endpoint bildirilmiş"
    else
        say 1 "${ad}: spring.ai.mcp.server.streamable-http.mcp-endpoint YOK — filtre korunacak
        yolu bu değerden türetir, açıkça bildirilmelidir"
    fi

    # 3) Sır düz metin olmasın. src/main gerçek dağıtımdır: ${...} olmalı.
    #    src/test yalnız context yüklemek için sahte bir değer taşır, o muaf.
    main_props=()
    for f in "${app}"/src/main/resources/application*.properties; do main_props+=("${f}"); done
    if (( ${#main_props[@]} > 0 )) \
        && grep -hE '^[[:space:]]*zeus\.ai\.mcp\.token[[:space:]]*=' "${main_props[@]}" 2>/dev/null \
           | grep -qvE '=[[:space:]]*\$\{'; then
        say 1 "${ad}: src/main'de zeus.ai.mcp.token DÜZ METİN görünüyor — sır repoya yazılmaz,
        \${ZEUS_MCP_TOKEN:} gibi bir ortam değişkeni yer tutucusu kullanın"
    else
        say 0 "${ad}: src/main'de token düz metin DEĞİL (ortam değişkeninden)"
    fi
done

if (( denetlenen == 0 )); then
    echo "  ATLANDI: hiçbir uygulamada zeus.ai.mcp.enabled=true bildirilmemiş"
    exit 0
fi

exit ${fail}
