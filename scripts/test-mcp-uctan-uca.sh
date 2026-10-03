#!/usr/bin/env bash
#
# MCP ucunun UÇTAN UCA denetimi — gerçek sunucu, gerçek protokol.
#
# NEDEN AYRI BİR GUARD: kapsam denetimi (verify-module-coverage.sh) "jar module'de var mı?"
# sorusunu cevaplar; "sınıflar link olur / kurulur mu?" sorusunu cevaplayamaz. Bu ailenin iki
# ölçülmüş üyesi var: (1) spring-webflux module'e girince jakarta.websocket'in eksik kalması,
# (2) mcp-core'un @WebServlet'li üç transport sınıfının servlet sanılıp deploy'u düşürmesi
# (metadata-complete ile çözüldü). İKİSİ DE yeşil kapsamla birlikte yaşandı. Linking ve protokol
# davranışını yalnız gerçek bir deploy ölçebilir — bu script o ölçümü dondurur.
#
# İDDİALAR:
#   1. Fail-closed: token'sız ve YANLIŞ token'lı istek BİREBİR AYNI 401 + problem+json alır
#      (oracle yok — istemci "header mı eksik, değer mi yanlış" sorusunu ayırt edemez).
#   2. /sse 404 döner: deprecated SSE taşıması yayınlanmıyor (protocol=STREAMABLE kanıtı).
#      SSE yayında olsaydı ucu AYRI property'den geldiği için token filtresi onu korumazdı.
#   3. tools/list iki tool'u VE @ToolParam açıklamasını döner → mevcut anotasyonlar yeni bir
#      anotasyon yazılmadan MCP şemasına köprülenmiş.
#   4. tools/call gerçek veri döner (isError yok) ve tool'un kendi log satırı İSTEKLE AYNI
#      correlation-id'yi taşır; audit satırının thread'i bir İSTEK worker'ıdır (reaktif değil).
#      Bu son kısım, Spring AI'ın immediateExecution(true) davranışına dayanan varsayımın
#      üretimde süregelen denetimidir (R3).
#
# ÖN KOŞUL: WildFly çalışıyor + uygulama deploy edilmiş + ZEUS_MCP_TOKEN biliniyor.
# Sağlanamazsa ATLANDI der (sessiz yeşil değil — nedenini basar, exit 0).
#
# Detay: gelistirmeler/23-zeus-ai-mcp.md
set -uo pipefail
FW_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WILDFLY_HOME="${WILDFLY_HOME:-/Users/omer/workspaces/intellij/wildfly-41/wildfly-41.0.0.Final}"
CONTEXT="${ZEUS_MCP_CONTEXT:-/spring-wildfly-arch}"
ENDPOINT="${ZEUS_MCP_ENDPOINT:-/api/mcp}"
# localhost DEĞİL 127.0.0.1: WildFly yalnız IPv4 loopback'i dinler; macOS'ta 'localhost' önce
# ::1'e çözülür ve 8080'i tutan BAŞKA bir süreç (ör. bir Docker container'ı) varsa istek ona
# gider. Bu, ilk ölçümde gerçekten yaşandı.
HOSTPORT="${ZEUS_MCP_HOSTPORT:-127.0.0.1:8080}"
BASE="http://${HOSTPORT}${CONTEXT}${ENDPOINT}"
LOG="${WILDFLY_HOME}/standalone/log/server.log"

fail=0
say() { if (( $1 == 0 )); then echo "  ✅ $2"; else echo "  ❌ $2"; fail=1; fi; }
atla() { echo "  ATLANDI: $1"; exit 0; }

command -v curl >/dev/null 2>&1 || atla "curl yok"
TOKEN="${ZEUS_MCP_TOKEN:-}"
[[ -n "${TOKEN}" ]] || atla "ZEUS_MCP_TOKEN tanımlı değil (sunucunun gördüğü sır bilinmeden ölçüm yapılamaz)"
curl -s -o /dev/null --max-time 5 "http://${HOSTPORT}${CONTEXT}/api/products" \
    || atla "uygulama ${HOSTPORT}${CONTEXT} üzerinde yanıt vermiyor (WildFly kapalı veya deploy yok)"

H_CT='Content-Type: application/json'
H_ACC='Accept: application/json, text/event-stream'
TMP="$(mktemp -d)"
trap 'rm -rf "${TMP}"' EXIT

# ── 1) Fail-closed -----------------------------------------------------------------
curl -s --max-time 10 -o "${TMP}/no.body" -D "${TMP}/no.hdr" -X POST "${BASE}" \
    -H "${H_CT}" -H "${H_ACC}" -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
curl -s --max-time 10 -o "${TMP}/wr.body" -D "${TMP}/wr.hdr" -X POST "${BASE}" \
    -H "${H_CT}" -H "${H_ACC}" -H "X-Zeus-Mcp-Token: kesinlikle-yanlis" \
    -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'

grep -q '401' <<< "$(head -1 "${TMP}/no.hdr")" \
    && say 0 "token'sız istek 401" || say 1 "token'sız istek 401 (gelen: $(head -1 "${TMP}/no.hdr"))"
grep -qi 'application/problem+json' "${TMP}/no.hdr" \
    && say 0 "red yanıtı problem+json" || say 1 "red yanıtı problem+json"
if cmp -s "${TMP}/no.body" "${TMP}/wr.body"; then
    say 0 "eksik ve yanlış token BİREBİR aynı gövdeyi alıyor (oracle yok)"
else
    say 1 "eksik ve yanlış token FARKLI gövde alıyor — oracle sızıyor"
fi
grep -q "${TOKEN}" "${TMP}/no.body" "${TMP}/wr.body" 2>/dev/null \
    && say 1 "red gövdesi token SIZDIRIYOR" || say 0 "red gövdesi token sızdırmıyor"

# ── 2) SSE yayında olmamalı ---------------------------------------------------------
sse_code="$(curl -s --max-time 10 -o /dev/null -w '%{http_code}' -X POST \
    "http://${HOSTPORT}${CONTEXT}/sse" -H "${H_ACC}")"
[[ "${sse_code}" == "404" ]] \
    && say 0 "/sse 404 (deprecated SSE taşıması yayınlanmıyor)" \
    || say 1 "/sse ${sse_code} döndü — SSE taşıması YAYINDA olabilir ve token filtresi onu KORUMAZ"

# ── 3) initialize + tools/list ------------------------------------------------------
curl -s --max-time 15 -D "${TMP}/i.hdr" -o "${TMP}/i.out" -X POST "${BASE}" \
    -H "${H_CT}" -H "${H_ACC}" -H "X-Zeus-Mcp-Token: ${TOKEN}" \
    -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"zeus-guard","version":"1"}}}'
# grep -i: BSD awk'ta IGNORECASE yok, header adı büyük/küçük harf duyarsızdır.
SID="$(grep -i '^mcp-session-id:' "${TMP}/i.hdr" | awk '{print $2}' | tr -d '\r')"
if [[ -z "${SID}" ]]; then
    say 1 "initialize Mcp-Session-Id döndürmedi (ölçümün geri kalanı yapılamadı)"
    exit ${fail}
fi
say 0 "initialize oturum açtı"

curl -s --max-time 10 -o /dev/null -X POST "${BASE}" -H "${H_CT}" -H "${H_ACC}" \
    -H "X-Zeus-Mcp-Token: ${TOKEN}" -H "Mcp-Session-Id: ${SID}" \
    -d '{"jsonrpc":"2.0","method":"notifications/initialized"}'

curl -s --max-time 15 -o "${TMP}/list.out" -X POST "${BASE}" -H "${H_CT}" -H "${H_ACC}" \
    -H "X-Zeus-Mcp-Token: ${TOKEN}" -H "Mcp-Session-Id: ${SID}" \
    -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'

for tool in listProducts findProductById; do
    grep -q "\"${tool}\"" "${TMP}/list.out" \
        && say 0 "tools/list '${tool}' içeriyor" || say 1 "tools/list '${tool}' İÇERMİYOR"
done
# @ToolParam açıklaması şemaya geçmiş mi? "Açıklama bir yorum değil, ARAYÜZ SÖZLEŞMESİDİR":
# model tool'u ve parametresini bu metinle seçer.
grep -q 'Ürünün sayısal' "${TMP}/list.out" \
    && say 0 "@ToolParam açıklaması input şemasında" \
    || say 1 "@ToolParam açıklaması şemada YOK — köprü bozulmuş olabilir"

# ── 4) tools/call + correlation-id + thread -----------------------------------------
CORR="zeusguard$(date +%s)$$"
curl -s --max-time 20 -o "${TMP}/call.out" -X POST "${BASE}" -H "${H_CT}" -H "${H_ACC}" \
    -H "X-Zeus-Mcp-Token: ${TOKEN}" -H "Mcp-Session-Id: ${SID}" -H "X-Correlation-Id: ${CORR}" \
    -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"findProductById","arguments":{"id":1}}}'

if grep -q '"isError":false' "${TMP}/call.out" && grep -q '"result"' "${TMP}/call.out"; then
    say 0 "tools/call başarılı sonuç döndü"
else
    say 1 "tools/call başarısız: $(head -c 300 "${TMP}/call.out")"
fi

if [[ ! -r "${LOG}" ]]; then
    say 1 "server.log okunamadı (${LOG}) — correlation-id/thread iddiaları ÖLÇÜLEMEDİ"
    exit ${fail}
fi
sleep 1   # log yazımı için küçük pay
corr_lines="$(grep -F "${CORR}" "${LOG}" 2>/dev/null)"
grep -q 'ZeusMcpAuditor' <<< "${corr_lines}" \
    && say 0 "audit satırı istekle AYNI correlation-id'yi taşıyor" \
    || say 1 "audit satırı bulunamadı / correlation-id taşımıyor"
grep -q 'ProductAiTools' <<< "${corr_lines}" \
    && say 0 "tool'un kendi log satırı da aynı correlation-id'de (veri yolu PRODUCT_PKG)" \
    || say 1 "tool'un log satırı aynı correlation-id'de YOK"

thread="$(grep -o 'thread=[^,]*' <<< "${corr_lines}" | head -1 | cut -d= -f2-)"
if [[ -z "${thread}" ]]; then
    say 1 "audit satırında thread alanı yok — R3 denetimi yapılamadı"
elif grep -qiE 'reactor|boundedElastic|parallel-' <<< "${thread}"; then
    say 1 "tool REAKTİF thread'de koştu ('${thread}') — immediateExecution varsayımı DÜŞTÜ (R3);
        MDC taşınmaz, correlation-id ve kimlik kaybolur. CorrelationId.capture()/restore()
        yedeğini devreye alın (bkz. gelistirmeler/23-zeus-ai-mcp.md, R3)"
else
    say 0 "tool İSTEK thread'inde koştu ('${thread}') — R3 tetiklenmedi"
fi

exit ${fail}
