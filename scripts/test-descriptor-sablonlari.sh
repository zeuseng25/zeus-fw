#!/usr/bin/env bash
#
# ÜRETİLEN DESCRIPTOR ŞABLONLARI GUARD'I (PLATFORM scripti).
#
# NEDEN VAR: `zeus-war-defaults/src/main/resources/descriptor-*/jboss-deployment-structure.xml`
# dosyaları, deploy edilen her WAR'ın WEB-INF'ine BİREBİR kopyalanır (yalnız ${...} slot
# yer tutucuları doldurulur). Yani bu dört dosya, uygulamanın WildFly'daki sınıf yükleme
# davranışının TEK KAYNAĞIDIR — ve bu dalın hiçbir guard'ı YOKTU:
#   - verify-module-coverage.sh yalnız BUILD EDİLMİŞ bir WAR'ın içinde `name="com.zeus"`
#     geçtiğine bakar (attribute'lara, module kümesine, diğer tiplere HİÇ bakmaz),
#   - test-soap-slot-property.sh şablonlara yalnız bir YORUM satırında değinir.
# Sonuç (ölçüldü): descriptor-standard'dan `annotations="true"` silindiğinde süit 11/11
# YEŞİL, `generate-war-excludes.sh --check` YEŞİL, eşitlik guard'ı YEŞİL kalıyordu; oysa
# @HandlesTypes(WebApplicationInitializer) taraması çözülemez hale gelir, DispatcherServlet
# hiç kurulmaz ve HER standart tip uygulama runtime'da 404 döner. Bu dal, şablonun
# descriptor içeriğinin TEK YETKİLİSİ yapılmasıyla (uygulama-başına opt-in mekanizmasının
# silinmesi) daha da kritikleşti.
#
# NE ÖLÇER (her şablon için):
#   1) Dosya XML olarak PARSE EDİLİYOR mu (bozuk XML'i WildFly deploy anında reddeder).
#   2) Beklenen <module> KÜMESİ birebir mi (eksik/fazla import ikisi de arıza).
#   3) Her module satırındaki YÜK TAŞIYAN attribute'lar yerinde mi (aşağıda her birinin
#      neyi kırdığı yazılıdır).
#   4) Slot yer tutucusu doğru property'yi mi gösteriyor (yanlışı → çözülmemiş ${...}).
#   5) exclude-subsystems: her tipte `logging`, YALNIZ soap tipinde `webservices`.
#
# TÜRETİLEN vs SABİTLENEN: şablon DİZİNLERİ diskten TÜRETİLİR (yeni bir tip eklenip guard'a
# yazılmazsa KIRMIZI olur — sessizce kapsam dışı kalamaz). Beklenen module kümesi ve
# attribute'lar SABİTTİR: bunlar mimari KARARLARDIR, türetilebilecekleri başka bir kaynak
# yoktur — kaynak olsaydı guard hiçbir şey ölçmezdi (şablonu şablondan doğrulamak).
#
# SESSİZ YEŞİL YASAK: hiç şablon bulunamazsa / beklenen bir şablon diskte yoksa bu bir
# ÖLÇÜM HATASIDIR, KIRMIZI döner.
#
# Kullanım: ./scripts/test-descriptor-sablonlari.sh
#
set -uo pipefail

FW_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RES_DIR="${FW_ROOT}/zeus-war-defaults/src/main/resources"

if [[ ! -d "${RES_DIR}" ]]; then
    echo "  ❌ ölçüm yapılamadı: şablon kök dizini yok: ${RES_DIR}"
    exit 1
fi

RES_DIR="${RES_DIR}" python3 - <<'PY'
import os, re, sys
import xml.etree.ElementTree as ET

res = os.environ['RES_DIR']
NS = '{urn:jboss:deployment-structure:1.3}'
fail = 0

def ok(msg):
    print("  ✅ %s" % msg)

def bad(msg, *extra):
    global fail
    fail = 1
    print("  ❌ %s" % msg)
    for e in extra:
        print("     %s" % e)

# ── BEKLENTİ TABLOSU ────────────────────────────────────────────────────────────────────
# modules: (module adı -> slot yer tutucusu). Boş sözlük = <dependencies> bloğu HİÇ OLMAMALI.
#
# YÜK TAŞIYAN ATTRIBUTE'LAR — her biri silindiğinde NE KIRILIR (bu yorum guard'ın asıl
# değeridir; bir sonraki okuyucu "ne işe yarıyordu?" diye sormasın):
#   annotations="true" : module jar'larındaki Jandex index'leri deployment'ın composite
#       index'ine katar. Olmadan @HandlesTypes(WebApplicationInitializer) taraması
#       deployment sınıfını module'deki üst hiyerarşiye BAĞLAYAMAZ → SpringServletContainer-
#       Initializer hiç tetiklenmez → DispatcherServlet kurulmaz → uygulama 404 döner.
#       (Arıza SESSİZDİR: deploy BAŞARILI görünür.)
#   services="import"  : module'deki META-INF/services kayıtlarını görünür kılar
#       (ServletContainerInitializer, JPA PersistenceProvider, CXF bus extension'ları,
#       SLF4J provider...). Olmadan ServiceLoader hiçbirini bulamaz.
#   meta-inf="import"  : META-INF altındaki DİĞER kaynaklar (spring.factories,
#       spring/*.imports, META-INF/cxf/*). Olmadan Spring Boot auto-configuration ve CXF
#       bus extension keşfi çalışmaz.
EXPECT = {
    'descriptor-standard': {
        'modules': {'com.zeus': '${zeus.module.slot}'},
        'subsystems_must':    ['logging', 'weld', 'batch-jberet', 'jsf', 'jaxrs'],
        'subsystems_mustnot': ['webservices'],
        'why': 'standart ince WAR: tüm 3. parti com.zeus module\'ünden gelir',
    },
    'descriptor-soap': {
        # com.zeus.soap BUGÜN BOŞ (0 jar) ama import EDİLMEYE devam eder: SOAP tipi ile
        # standart tip arasındaki yapısal ayrım / ileride CXF ayrışması için ayrılmış dikiş.
        # webservices dışlaması bundan BAĞIMSIZ bir descriptor direktifidir.
        'modules': {'com.zeus': '${zeus.module.slot}',
                    'com.zeus.soap': '${zeus.soap.module.slot}'},
        'subsystems_must':    ['logging', 'weld', 'batch-jberet', 'jsf', 'jaxrs', 'webservices'],
        'subsystems_mustnot': [],
        'why': 'SOAP tipi: konteynerin JAX-WS\'i dışlanır, CXF com.zeus\'tan gelir',
    },
    'descriptor-bff': {
        # FAT WAR: module import ETMEK çift kopya yaratır → ClassCastException.
        'modules': {},
        'subsystems_must':    ['logging', 'weld', 'batch-jberet', 'jsf', 'jaxrs'],
        'subsystems_mustnot': ['webservices'],
        'why': 'BFF fat WAR: com.zeus\'a BAĞLANMAZ (dependencies bloğu OLMAMALI)',
    },
    'descriptor-standalone': {
        # SELF-CONTAINED WAR: zeus-* dahil her şey WEB-INF/lib'de; module import edilmez.
        'modules': {},
        'subsystems_must':    ['logging', 'weld', 'batch-jberet', 'jsf', 'jaxrs'],
        'subsystems_mustnot': ['webservices'],
        'why': 'standalone self-contained WAR: com.zeus\'a BAĞLANMAZ (dependencies bloğu OLMAMALI)',
    },
}
LOAD_BEARING_ATTRS = {'services': 'import', 'meta-inf': 'import', 'annotations': 'true'}

# ── 1) Şablon dizinlerini DİSKTEN türet ve beklenti tablosuyla İKİ YÖNLÜ eşle ───────────
found = sorted(d for d in os.listdir(res)
               if d.startswith('descriptor-') and os.path.isdir(os.path.join(res, d)))
if not found:
    print("  ❌ ölçüm yapılamadı: %s altında HİÇ descriptor-* şablon dizini yok" % res)
    sys.exit(1)

missing = sorted(set(EXPECT) - set(found))
extra   = sorted(set(found) - set(EXPECT))
if missing:
    bad("beklenen şablon dizini DİSKTE YOK: %s" % ', '.join(missing),
        "Bir tip şablonu silinmiş/yeniden adlandırılmışsa o tipin WAR'ı descriptor'sız kalır.")
if extra:
    bad("guard'ın TANIMADIĞI şablon dizini var: %s" % ', '.join(extra),
        "Yeni bir uygulama tipi eklenmiş ama beklentisi bu guard'a yazılmamış —",
        "descriptor içeriği ÖLÇÜLMEDEN deploy edilir. EXPECT tablosuna ekleyin.")
if not missing and not extra:
    ok("şablon dizinleri diskten türetildi ve beklenti tablosuyla birebir eşleşiyor (%d tip)" % len(found))

# ── 2) Her şablonu ayrı ayrı ölç ────────────────────────────────────────────────────────
for name in sorted(set(found) & set(EXPECT)):
    exp = EXPECT[name]
    path = os.path.join(res, name, 'jboss-deployment-structure.xml')
    if not os.path.isfile(path):
        bad("[%s] jboss-deployment-structure.xml YOK: %s" % (name, path))
        continue

    raw = open(path, encoding='utf-8').read()
    # XML PARSE: ${...} yer tutucuları attribute değeri olarak geçerlidir, parse'ı bozmaz.
    try:
        root = ET.fromstring(raw)
    except ET.ParseError as e:
        bad("[%s] XML PARSE EDİLEMİYOR: %s" % (name, e),
            "WildFly bozuk descriptor'ı deploy anında reddeder — WAR hiç açılmaz.")
        continue

    dep = root.find('%sdeployment' % NS)
    if dep is None:
        bad("[%s] <deployment> elemanı yok" % name)
        continue

    # 2a) <dependencies> / <module> kümesi
    deps_el = dep.find('%sdependencies' % NS)
    mods = {}
    if deps_el is not None:
        for m in deps_el.findall('%smodule' % NS):
            mods[m.get('name')] = m

    if not exp['modules']:
        if deps_el is not None:
            bad("[%s] <dependencies> bloğu VAR ama OLMAMALI (%s)" % (name, exp['why']),
                "Fat/self-contained WAR'da module import etmek AYNI SINIFIN İKİ KOPYASINI",
                "yaratır (module + deployment classloader) → ClassCastException.")
        else:
            ok("[%s] <dependencies> bloğu YOK — doğru (%s)" % (name, exp['why']))
        # module'ü olmayan tipte attribute kontrolüne gerek yok.
    else:
        if deps_el is None:
            bad("[%s] <dependencies> bloğu YOK ama OLMALI: %s" % (name, ', '.join(sorted(exp['modules']))),
                "Bu WAR ince'dir: 3. parti jar'lar WAR'dan DIŞLANMIŞTIR; module import",
                "edilmezse runtime'da NoClassDefFoundError.")
        elif set(mods) != set(exp['modules']):
            bad("[%s] <module> kümesi BEKLENENDEN FARKLI" % name,
                "beklenen: %s" % ', '.join(sorted(exp['modules'])),
                "bulunan : %s" % (', '.join(sorted(k for k in mods if k)) or '(yok)'))
        else:
            ok("[%s] <module> kümesi doğru: %s" % (name, ', '.join(sorted(exp['modules']))))

        # 2b) YÜK TAŞIYAN attribute'lar + slot yer tutucusu
        for mname, slot in sorted(exp['modules'].items()):
            m = mods.get(mname)
            if m is None:
                continue  # küme farkı yukarıda zaten raporlandı
            for attr, val in sorted(LOAD_BEARING_ATTRS.items()):
                got = m.get(attr)
                if got != val:
                    bad("[%s] <module name=\"%s\"> için %s=\"%s\" EKSİK/YANLIŞ (bulunan: %r)"
                        % (name, mname, attr, val, got),
                        "Bu attribute'un ne işe yaradığı bu script'in EXPECT tablosu üstünde",
                        "yazılıdır; silinmesi deploy'u BAŞARILI gösterir ama uygulamayı",
                        "runtime'da bozar (annotations=true için: 404).")
            if m.get('slot') != slot:
                bad("[%s] <module name=\"%s\"> slot yer tutucusu yanlış: beklenen %s, bulunan %r"
                    % (name, mname, slot, m.get('slot')),
                    "Yanlış/eksik property adı build'de ÇÖZÜLMEZ; WildFly '${...}' adlı bir",
                    "slot arar ve deployment açılışta patlar.")
        if all(mods.get(mn) is not None
               and all(mods[mn].get(a) == v for a, v in LOAD_BEARING_ATTRS.items())
               and mods[mn].get('slot') == sl
               for mn, sl in exp['modules'].items()):
            ok("[%s] yük taşıyan attribute'lar + slot yer tutucuları yerinde (%s)"
               % (name, ', '.join('%s="%s"' % kv for kv in sorted(LOAD_BEARING_ATTRS.items()))))

    # 2c) exclude-subsystems
    ex_el = dep.find('%sexclude-subsystems' % NS)
    subs = set()
    if ex_el is not None:
        subs = {s.get('name') for s in ex_el.findall('%ssubsystem' % NS)}
    miss = [s for s in exp['subsystems_must'] if s not in subs]
    forb = [s for s in exp['subsystems_mustnot'] if s in subs]
    if miss:
        bad("[%s] dışlanması GEREKEN subsystem(ler) eksik: %s" % (name, ', '.join(miss)),
            "logging: WildFly kendi log manager'ını dayatır (Spring Boot Logback kırılır).",
            "weld/batch-jberet/jsf/jaxrs: kullanılmayan subsystem'ler module sınıflarını",
            "tarayıp link etmeye çalışır ve deployment hata verir.",
            "webservices (yalnız SOAP tipi): konteynerin JBossWS/CXF'i bizim CXF'imizle çakışır.")
    if forb:
        bad("[%s] dışlanmaması GEREKEN subsystem dışlanmış: %s" % (name, ', '.join(forb)),
            "webservices dışlaması YALNIZ SOAP tipine aittir.")
    if not miss and not forb:
        ok("[%s] exclude-subsystems doğru (%d dışlama)" % (name, len(subs)))

sys.exit(fail)
PY
rc=$?
if (( rc != 0 )); then
    echo "  ❌ descriptor şablonları BOZUK — deploy edilen her WAR bu içeriği BİREBİR taşır"
else
    echo "  ✅ dört descriptor şablonu da sözleşmesine uygun"
fi
exit ${rc}
