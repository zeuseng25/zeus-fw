# 17 — Paylaşımlı Module Yenileme Runbook'u (com.zeus + com.zeus.soap)

BOM'da 3. parti bir sürüm değiştiğinde paylaşımlı WildFly module'leri **bayatlar**.
Bu doküman yenileme prosedürünü ve 2026-08-28'de yapılan yenilemenin somut kaydını içerir.

Mimari arka plan: `08-wildfly-module-dagitim.md` · slot politikası:
`10-versiyonlu-slot-uretilen-descriptor.md` · BOM kapsamı: `16-eol-bagimliliklar-ve-migrasyon.md`.

## Ne Zaman Gerekir

| Değişen şey | Module yenileme | WildFly restart |
|---|---|---|
| **3. parti sürüm** (BOM'da bump / CVE yaması) | **GEREKİR** | **GEREKİR** (main slot'ta) |
| Yeni 3. parti bağımlılık | **GEREKİR** (önce `zeus-wildfly-module/pom.xml`'e ekle) | **GEREKİR** |
| zeus-* modül kodu | Gerekmez (zeus jar'ları WAR'da) | Gerekmez |
| Uygulama kodu | Gerekmez | Gerekmez — WAR redeploy yeter |
| Yeni **versiyonlu slot** kurulumu | Gerekir | **Gerekmez** (module'ler ilk referansta yüklenir) |

Restart'ın sebebi: WildFly module **tanımını cache'ler**. `main` slot'unun içeriği
değiştiğinde çalışan sunucu eski jar setini tutmaya devam eder.

## Prosedür

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@25          # build Java 25 ister
export WILDFLY_HOME=/Users/omer/workspaces/intellij/wildfly-41/wildfly-41.0.0.Final

cd ../zeus-fw
mvn -B install -DskipTests            # 1) BOM + modüller m2'ye kurulmalı (script m2'den okur)
./scripts/install-zeus-module.sh                    # 2) com.zeus  (temel Spring yığını)
./scripts/install-zeus-module.sh --module soap      # 3) com.zeus.soap (CXF yığını)
# 4) WildFly çalışıyorsa yeniden başlat
```

Sonra tüketen uygulamada:

```bash
./scripts/verify-module-coverage.sh   # kapsam denetimi (deploy'dan ÖNCE)
./scripts/deploy.sh --no-build        # deploy.sh denetimi kendisi de çalıştırır
```

### ⚠️ SOAP module'ünü unutma

`verify-module-coverage.sh` **çalıştırıldığı uygulamanın** bağımlılıklarını denetler.
`spring-wildfly-arch` SOAP tipi olmadığı için `com.zeus.soap`'a hiç bakmaz — CXF sürümü
değişmişse bunu **yakalamaz**. BOM'da `cxf.version` değiştiyse soap module'ünü elle
yenile, ya da denetimi bir SOAP tipi uygulamada çalıştır.

### Hedef sunucu seçimi

`WILDFLY_HOME` hangi sunucuya kurulacağını belirler — staging/prod ayrımı buradan yapılır:

```bash
WILDFLY_HOME=/path/staging-wildfly ./scripts/install-zeus-module.sh --slot 1.1.0
```

`main` slot'u mutable'dır (üzerine yazılır). Versiyonlu slot'lar **immutable**'dır;
script üzerine yazmayı reddeder (bilinçli yeniden üretim için `FORCE=1`).

## 2026-08-28 Yenilemesi — Ne Değişti

Tetikleyici: `zeus-dependencies` BOM'unun kurum envanterine göre genişletilmesi
(`16-eol-bagimliliklar-ve-migrasyon.md`). Üç sürüm hattı ilerledi.

**`com.zeus:main` — 157 jar (sayı değişmedi, 22 jar sürüm atladı)**

| Kütüphane | Önce | Sonra |
|---|---|---|
| `spring-ai-*` (14 jar) | 2.0.0 | **2.0.1** |
| `openai-java-core` | 4.39.1 | **4.49.0** |
| `springdoc-openapi-*` (3 jar) | 3.0.3 | **3.1.0** |
| `swagger-core/models-jakarta` | 2.2.47 | **2.2.52** |
| `swagger-ui` | 5.32.2 | **5.32.11** |

**`com.zeus.soap:main` — 23 jar (41 jar küme farkıyla atlandı)**

| Kütüphane | Önce | Sonra |
|---|---|---|
| `cxf-*` (13 jar) | 4.2.2 | **4.2.3** |
| `neethi` | 3.2.2 | **3.2.3** |
| `woodstox-core` | 7.2.0 | **7.2.1** |

> `neethi` ve `woodstox` BOM'da **pin'li değil** — `cxf-bom:4.2.3` ile birlikte kendiliğinden
> ilerlediler. "Transitif taşıyıcı pin'lenmez" kuralının (bkz. `16-*.md`) pratikteki karşılığı
> tam olarak budur: pin'li olsalardı CXF 4.2.3 eski transitiflerle çalışmaya zorlanırdı.

## Doğrulama Zinciri (yapıldı)

1. **Kapsam denetimi** — yenileme öncesi 21 jar eksik raporlandı; sonrasında
   `✅ Module kapsamı tam`.
2. **Deploy** — `deploy.sh --no-build` → `✅ DEPLOY BAŞARILI`, Undertow context
   `/spring-wildfly-arch` kaydedildi.
3. **Uçtan uca** (WildFly 4.8 sn'de açıldı):
   - `GET /api/products` → **200**, Oracle stored procedure'lerinden 5 kayıt
   - `GET /swagger-ui/index.html` → **200** (springdoc 3.1.0)
   - `GET /v3/api-docs` → **200**, `"openapi":"3.1.0"`
   - `GET /api/ai/products/1/description` → 502 · `401 Missing Authentication header`
     (beklenen: `AI_API_KEY` set değil)
4. **Sınıf yükleme** — log'da `NoClassDefFoundError` / `LinkageError` / `NoSuchMethodError`
   **sıfır**. AI stack trace'i yeni jar'ın module'den yüklendiğini kanıtlıyor:
   `at com.zeus//com.openai...~[openai-java-core-4.49.0.jar!/:4.49.0]`
   (`com.zeus//` öneki = module classloader; sürüm 4.39.1 değil 4.49.0).

## Script'in YÖNETMEDİĞİ module: `com.oracle.ojdbc`

Oracle JDBC sürücüsü `com.zeus`'ta **değildir** — WildFly'ın kendi
`modules/com/oracle/ojdbc/main/` module'ünde durur ve `standalone.xml`'deki
datasource tanımı ona `driver-name` ile bağlanır. Bu module'ü
`install-zeus-module.sh` **üretmez**; elle kurulmuştur.

Sonuç: BOM'da `ojdbc.version` yükseltmek bu module'ü **değiştirmez** → sessiz drift.
Bu tam olarak 2026-08-28'de oldu (BOM 23.26.3.0.0, module hâlâ 23.9.0.25.07:
`wildfly` profili eski sürücüyle, `local` profili yenisiyle çalışıyordu).

Elle yükseltme (WildFly kapalıyken):

```bash
WF=$WILDFLY_HOME; MD="$WF/modules/com/oracle/ojdbc/main"; V=23.26.3.0.0
BASE=https://repo1.maven.org/maven2/com/oracle/database/jdbc/ojdbc17/$V/ojdbc17-$V.jar
curl -sS -o "$MD/ojdbc17-$V.jar" "$BASE"
# bütünlük şart — module'e bozuk jar koymak sessiz ClassFormatError üretir
diff <(curl -sS "$BASE.sha1" | tr -d ' \n') <(shasum -a 1 "$MD/ojdbc17-$V.jar" | awk '{print $1}')
sed -i '' "s|ojdbc17-<ESKİ>.jar|ojdbc17-$V.jar|" "$MD/module.xml"
rm "$MD/ojdbc17-<ESKİ>.jar"
```

Doğrulama:

```bash
$WILDFLY_HOME/bin/jboss-cli.sh --connect \
  --command="/subsystem=datasources/data-source=OracleDS:test-connection-in-pool"
# => "outcome" => "success", "result" => [true]
```

**Sürüm nereden geliyor — ÖNEMLİ.** Oracle ailesinin sürümü `zeus-dependencies`'te
DEĞİL, **kök `pom.xml`'deki `oracle-database.version`** property'sindedir. Sebebi Maven
öncelik kuralıdır: Spring Boot BOM'u bu aileyi zaten yönetir ve `spring-boot-starter-parent`
parent zinciriyle miras alınır; `zeus-dependencies`'teki bir giriş IMPORT olduğu için
miras alınana kaybeder ve **sessizce etkisiz kalır**. Boot parent'ken bir Boot sürümünü
ezmenin tek yolu Boot'un property'sini aynı adla yeniden tanımlamaktır.

Bu tuzağa bir kez düşüldü: BOM'da `ojdbc.version` 23.26.3.0.0'a çekildi, module de öyle
yükseltildi, ama uygulamalar Boot'un 23.9.0.25.07'siyle derlenmeye devam etti — yani
"drift kapatıldı" sanılırken ters yönde gerçek bir uyumsuzluk yaratıldı. Doğrulama:

```bash
mvn -pl <app> dependency:tree -Dincludes=com.oracle.database.jdbc
# => module'deki jar sürümüyle AYNI olmalı
```

**Kural:** kök pom'da `oracle-database.version` her değiştiğinde bu module de elle
yükseltilmeli. `verify-module-coverage.sh` bunu yakalamaz — üstelik ojdbc artık onun
EXCLUDE_REGEX'inde (module'de olmaması DOĞRU olduğu için), dolayısıyla sürüm
uyumunu denetleyen otomatik bir mekanizma yok.

## Tuzaklar

- **`localhost:8080` yanıltır.** Bu makinede Docker `[::1]:8080`'i, WildFly ise
  `127.0.0.1:8080`'i dinliyor. `localhost` önce IPv6'ya çözüldüğü için curl Docker'a
  düşer ve alakasız bir JSON 404 döner. Smoke test'te **`http://127.0.0.1:8080`** kullan.
- **`/usr/libexec/java_home -v 25` çalışmaz** — JDK 25 Homebrew Cellar'da, sisteme
  link'li değil. `JAVA_HOME=/opt/homebrew/opt/openjdk@25` diye açıkça ver.
- **`mvn install` atlanırsa** script eski BOM'u m2'den okur ve module'ü eski
  sürümlerle üretir — sessizce yanlış sonuç.
- **WAR yeniden derlenmeli.** Module'deki jar sürümü ile WAR'ın derlendiği sürüm
  ayrışırsa `NoSuchMethodError` runtime'da çıkar; module yenilemesinden sonra
  uygulamaları da yeniden paketle.
