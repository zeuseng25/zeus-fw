# 16 — EOL Bağımlılıklar ve Migrasyon (zeus-dependencies kapsam kararları)

Bu doküman `zeus-dependencies` BOM'unun **neyi yönettiğini, neyi kasten yönetmediğini**
ve kurum envanterindeki ölü kütüphanelerin yerine ne konacağını tanımlar.

Kaynak: Boot 3.1.3 / Java 17 / WildFly 27 hattındaki kurum uygulamalarının birleşik
`mvn dependency:list` envanteri (292 artefakt). Envanter dosyası **repoya girmez**
(`.gitignore`); bu doküman ondan çıkan kararların kalıcı kaydıdır.

## Analiz Sonucu

BOM'ların `dependencyManagement` ağaçları alt-import'larıyla birlikte özyinelemeli
çözülüp envanterle karşılaştırıldı:

| | Adet |
|---|---|
| Envanterdeki toplam artefakt | 292 |
| İmport edilen BOM'ların **zaten yönettiği** | **195** |
| Karar gerektiren (BOM dışı) | 97 |
| → BOM'a alınan (canlı, paylaşılan) | **21 giriş** + 2 yeni BOM import'u |
| → Kasten alınmayan (transitif / sunucudan gelen) | ~58 |
| → **Yasaklı** (ölü / EOL) | 12 |

Ders: envanterin **üçte ikisi** zaten yönetiliyordu. Kurumsal BOM'un işi "her şeyi
pin'lemek" değil, **BOM'ların bıraktığı boşluğu** kapatmak.

## Yasaklı Liste — BOM'a GİRMEZ

Bu kütüphaneler `zeus-dependencies`'te yönetilmez. Bir uygulama bunlara ihtiyaç
duyduğunu söylüyorsa cevap sürüm vermek değil, **migrasyondur**.

| Kütüphane | Envanterdeki sürüm | Neden öldü | Yerine |
|---|---|---|---|
| `com.adobe.blazeds:blazeds-*` | 4.0.0.14931 | Adobe Flex/AMF; 2012'de terk edildi, `javax.servlet` bağımlı | REST + JSON uçları |
| `org.springframework.flex:spring-flex-core` | 1.5.2.RELEASE | BlazeDS köprüsü; Spring tarafı da arşivlendi | REST + JSON uçları |
| `com.microsoft.ews-java-api` | 2.0 | 2020'de arşivlendi; Microsoft EWS'i emekliye ayırıyor | Microsoft Graph SDK |
| `cglib:cglib-nodep` | 2.1_3 (2009) | Spring kendi paketlenmiş CGLIB'ini içerir; harici cglib Java 25'te bytecode hatası verir | Spring'in dahili proxy'si (`spring-core`) |
| `commons-httpclient` | 3.1 | 2011'de EOL, TLS 1.2+ desteklemez | `httpclient5` (Boot BOM yönetir) |
| `jakarta.xml.rpc:jakarta.xml.rpc-api` | 1.1.4 | JAX-RPC, Jakarta EE 9+'ta **kaldırıldı** | JAX-WS / CXF (`zeus-soap`) |
| `org.springframework.ldap:spring-ldap-core-tiger` | 2.4.1 | Java 5 "tiger" modülü; 3.x'te `spring-ldap-core`'a katıldı | `spring-ldap-core` (Boot BOM yönetir) |
| `commons-collections` | 3.2.2 | 3.x hattı jenerik öncesi; bilinen deserializasyon geçmişi | `commons-collections4` (BOM'da) |
| `commons-digester` | 2.1 | 2011; XML→bean eşlemesi için terk edilmiş yaklaşım | JAXB / Jackson |
| `commons-fileupload` | 1.5 | `javax.servlet` API'sine bağlı; Jakarta karşılığı hâlâ milestone (`2.0.0-M5`) | Spring'in kendi multipart desteği (`StandardServletMultipartResolver`) |
| `jline` | 2.14.6 | JLine 2 hattı bitti | JLine 3, ya da kaldır (sunucu uygulamasında terminal kütüphanesi gerekmez) |
| `com.thoughtworks.qdox` | 1.12.1 | Build zamanı aracı; runtime bağımlılığı olmamalı | Kaldır |
| `xml-resolver` | 1.2 | 2006; Apache tarafından arşivlendi | JDK'nın kendi `javax.xml.catalog` API'si |
| `org.bouncycastle:*-jdk15on` | 1.69 | `jdk15on` artefaktları terk edildi | `*-jdk18on` (BOM'da, 1.85) |
| `com.oracle.database.jdbc:ojdbc10` | 19.21.0.0 | JDK 10 hattı | `ojdbc17` (BOM'da) |

## Kırıcı Geçişler — Boot 4.0.7'nin dayattıkları

Bunlar bizim kararımız değil, **platformun** kararı. Uygulama ekipleri kod
değişikliğine hazırlanmalı:

| Alan | Envanter (Boot 3.1.3) | Boot 4.0.7 | Etki |
|---|---|---|---|
| Jackson | 2.15.2 | **3.1.4** (`jackson-2-bom` 2.21.4 geçiş için) | Paket `com.fasterxml.jackson` → `tools.jackson`; `ObjectMapper` API'si değişti |
| JUnit | Jupiter 5.9.3 | **6.0.3** | `junit-platform` 2.x; bazı `@Test` uzantı API'leri değişti |
| Hibernate | 6.2.7 | **7.2.19** | `hibernate.dialect` ve `@GeneratedValue` davranış farkları |
| Groovy | 4.0.14 (+ `groovy-all` 4.0.13 uyumsuzluğu) | **5.0.6** (Boot'un `groovy-bom`'u) | Groovy 5 kırıcı; script'ler gözden geçirilmeli |
| Servlet | jakarta.servlet 6.0.0 | **6.1.0** | Jakarta EE 11 / WildFly 41 hattı |
| Spring | Framework 6.0.11 | **7.0.8** | `RestTemplate` → `RestClient`, kaldırılan API'ler |
| Tomcat (gömülü) | 10.1.12 | 11.0.22 | WildFly'da **kullanılmaz**, `provided` kalmalı |

> Groovy notu: BOM'da `groovy-bom`'u ayrıca import ETMİYORUZ. Boot 4.0.7 zaten
> `groovy-bom:5.0.6`'yı import ediyor ve Boot import'u ilk sırada olduğu için
> kazanır. 4.0.x'te kalmak isteyen bir uygulama, Boot'tan **önce** `groovy-bom`
> import etmek zorunda kalır — bu, Boot'un test ettiği kombinasyondan sapmak
> demektir ve platform kararı olarak **tercih edilmedi**.

## Çakışmaların (ÇAKIŞMA) Kök Nedeni

Envanterdeki 25 çakışma satırının tamamı üç desenden birine giriyor:

1. **Uygulama pom'unda elle yazılmış sürüm** (`slf4j-api 2.0.7 + 2.0.12`,
   `logback 1.4.11 + 1.4.12`, `bcprov 1.77 + 1.78`). BOM merkezîleşince kapanır —
   uygulamalar artık sürüm yazmaz.
2. **BOM yerine tek tek artefakt pin'lenmesi** (`woodstox 6.5.0 + 6.5.1`,
   `wsdl4j 1.6.2 + 1.6.3`, `groovy-all` ile `groovy-*` ayrışması). Çözüm:
   `cxf-bom` ve `shedlock-bom` import'ları — bu sınıf yapısal olarak imkânsızlaşır.
3. **Aynı artefaktın iki scope'ta görünmesi** (`compile + provided`:
   `lombok`, `snakeyaml`, `jul-to-slf4j`, `spring-boot-starter`). Bu **gerçek bir
   çakışma değil**; ince WAR modelinde beklenen durumdur — jar `com.zeus`
   module'ünden gelir, WAR'a girmez.

## BOM'a Kasten Alınmayanlar

**Transitif taşıyıcılar** — `asm-*`, `checker-qual`, `error_prone_annotations`,
`opentest4j`, `apiguardian-api`, `objenesis`, `accessors-smart`, `stax2-api`,
`angus-activation`, `istack-commons-runtime`, `mimepull`, `stax-ex`, `jandex`,
`neethi`, `xmlschema-core`, `FastInfoset`, `streambuffer`, `hibernate-commons-annotations`,
`HdrHistogram`, `LatencyUtils`, `plexus-utils`, `abego-treelayout`, `SparseBitSet`,
`swagger-*`, `swagger-ui`.

Gerekçe: bunlar CXF / JAXB / Hibernate / JUnit / springdoc ağaçlarıyla gelir.
Pin'lemek, üst kütüphane sürüm atladığında **sessiz uyumsuzluk** üretir — hata
build'de değil runtime'da çıkar. İstisna yalnızca somut bir CVE'dir ve o durumda
giriş, gerekçe yorumuyla birlikte yazılır.

**Kolayca yanlış sınıflandırılanlar.** Envanterde `[compile]` görünen bazı satırlar
"uygulama bunu doğrudan kullanıyor" izlenimi verir, ama aslında transitiftir.
`dependency:list` çıktısı doğrudan ve transitif bağımlılığı ayırt etmez — pin
kararından önce ebeveyni izle:

| Artefakt | Envanterdeki hâli | Gerçek ebeveyni |
|---|---|---|
| `org.apache.ant:ant` (+ `-junit`, `-launcher`, `-antlr`) | 1.10.13 `[compile]` | `groovy-ant` → `ant` (compile) |
| `org.apache.ivy:ivy` | 2.5.1 `[compile]` | `groovy` core → `ivy` (runtime, **optional** — Grape/`@Grab`) |
| `org.eclipse.jdt:ecj` | 3.21.0 `[compile]` | Eski `jasperreports` 6.x; **7.x artık bağımlı değil** |

Üçü de BOM'a **alınmadı**. Sürümlerini Groovy ve JasperReports belirlemeli;
pin'lenselerdi Groovy 5 kendi test ettiği Ant sürümüyle çalışamazdı.

**WildFly'ın sağladıkları** — `tomcat-embed-*`, `tomcat-dbcp`, `tomcat-juli`,
`jakarta.*-api`, `metro-saaj`, `saaj-impl`, `jaxws-rt`, `gmbal-api-only`,
`management-api`, `ha-api`. Bunların BOM'a girmesi WAR'a sızma riski yaratır.
SOAP tipinde `descriptor-soap` zaten `webservices` subsystem'ini dışlıyor
(bkz. `14-uygulama-tipi-parentlar.md`).

## ÖNEMLİ — BOM'a girmek, module'e girmek DEĞİLDİR

İnce WAR modelinde bir jar'ın çalışması için **iki** koşul gerekir:

1. Sürümü BOM'da yönetiliyor olmalı → bu doküman + `zeus-dependencies`.
2. Jar'ın kendisi ya paylaşımlı `com.zeus` module'ünde olmalı, ya da uygulamanın
   `zeus.war.keep` istisnasıyla kendi WAR'ında taşınmalı.

`poi`, `jasperreports`, `openpdf`, `jfreechart`, `shedlock` gibi yeni BOM
girişleri **otomatik olarak module'e girmez**. Bunlardan paylaşımlı olması
gerekenler `zeus-wildfly-module` aggregator'üne eklenmeli ve module yeniden
üretilmelidir:

```bash
./scripts/install-zeus-module.sh          # sonra WildFly restart
./scripts/verify-module-coverage.sh       # deploy öncesi kapsam denetimi
```

Yalnızca tek uygulamanın kullandığı bir kütüphane module'e **girmemeli**; o uygulama
`zeus.war.keep` ile kendi WAR'ında taşır. Detay: `08-wildfly-module-dagitim.md`.

## Yeni Kütüphane Ekleme Prosedürü

1. **BOM'lar zaten yönetiyor mu?** Kontrol: `mvn dependency:tree` ya da
   `mvn help:effective-pom -pl zeus-dependencies`. Yönetiyorsa **hiçbir şey yapma**.
2. **Resmî BOM'u var mı?** Varsa tek tek artefakt yerine `scope=import` ekle.
3. **Transitif mi?** Başka bir kütüphaneyle geliyorsa pin'leme.
4. **Sunucudan mı geliyor?** `jakarta.*`, servlet konteyneri, webservices → ekleme.
5. Kalanı `zeus-dependencies`'e property + `dependencyManagement` girişi olarak,
   **gerekçe yorumuyla** ekle.
6. Paylaşımlı olacaksa `zeus-wildfly-module`'e de ekle + module'ü yenile.

Sürüm seçerken Maven Central'ın arama indeksine (`search.maven.org`) **güvenme** —
ciddi şekilde bayat kalabiliyor. Otoritatif kaynak:
`https://repo1.maven.org/maven2/<group-yolu>/<artifact>/maven-metadata.xml`.
