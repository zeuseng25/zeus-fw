# zeus-ai-agent: araştırma ajanı harness'ı (Spring AI 2.x üzerinde)

**Tarih:** 2026-10-03 · **Durum:** tasarım, onay bekliyor · **Kapsam:** P1 — harness; P2 (MCP client) ve P3 (RAG) ayrı

## Problem

Zeus uygulamaları bugün LLM'e **tek soru** sorabiliyor (`ZeusAiAssistant.ask`), ve MCP ile
yeteneklerini **dışarıdaki** bir ajana açabiliyor (`zeus-ai-mcp`). Eksik olan üçüncü şey:
uygulamanın **kendi** ajanını yazabilmesi — çok adımlı, uzun soluklu, ara çıktı üreten bir koşu.

Hedef senaryo (kullanıcı kararı): **araştırma / analiz ajanı.** Çok sayıda kaynağı tarayıp uzun
bir çıktı üretir. Kaynaklar üç grup: kurum içi servisler (bugünkü `@Tool`), kurumsal doküman
tabanı, dış MCP sunucuları.

### Parçalama — bu spec neyi kapsamıyor

Üç kaynak üç bağımsız alt projedir ve ikisi zeus-fw'da hiç yok:

| # | Alt proje | Durum |
|---|---|---|
| **P1 — agent harness** (bu spec) | Ajanın var olabilmesi | Tasarlanıyor |
| P2 — MCP client | Dış MCP sunucuları tool kaynağı olur | Roadmap P2 / 2027-Q1, 4 AG |
| P3 — doküman erişimi / RAG | Bilgi tabanı aranabilir olur | **Hiç yok** (embedding/vektör deposu yok) |

P2 ve P3 ajanın *neyi göreceğini* genişletir; P1 ajanın *var olabilmesini* sağlar. P1 önce
geliyor çünkü diğer ikisi onsuz yalnızca "daha fazla tool" demek — bugünkü `ask()` ile de
çağrılabilirler. P3 ayrıca en belirsiz olan (vektör deposu seçimi: Oracle 23ai AI Vector Search
mı, dışarıda bir şey mi; embedding modeli nerede koşacak) ve ajanın erişimi gerçekte nasıl
tükettiğini görmeden o arayüzü tasarlamak yanlış tasarlamak olur.

## Ölçülen gerçekler — Spring AI neyi hazır veriyor

deepagents'ın LangGraph'a ihtiyaç duymasının sebebi, Python tarafında **genişletilebilir bir tool
döngüsünün olmamasıydı**. Spring AI 2.0 tam o genişletilebilirliği veriyor. Kurulu
`spring-ai-model` / `spring-ai-client-chat` 2.0.1 jar'larından okundu (2026-10-03):

| Parça | Ne sağlıyor |
|---|---|
| `ToolCallingAdvisor` | Tool döngüsü, **alt sınıflama için tasarlanmış**: `protected` ctor, self-tipli `Builder<T extends Builder<T>>` + `newCopy()`, ve altı `protected` kanca: `doInitializeLoop`, `doBeforeCall`, `doGetNextInstructionsForToolCall`, `doAfterCall`, `doFinalizeLoop` (+ stream eşlenikleri) |
| `ToolExecutionEligibilityChecker` | `Function<ChatResponse, Boolean>` — döngünün devam edip etmeyeceğine karar verir |
| `ToolCallingManager` | Takılabilir (`Builder.toolCallingManager(...)`); her tool yürütmesini sarmalar |
| `ToolExecutionResult.conversationHistory()` | Tool sonuçlarının mesaj listesi |
| `ChatClientRequest` | Record; `prompt()` + `context()` map, `mutate()` ile kopyalanabilir |
| `ToolContext` | Tool'lara geçen `Map<String,Object>` |
| `ChatMemory` | `add`/`get`/`clear(conversationId)`; `MessageWindowChatMemory` + `InMemoryChatMemoryRepository` |

**Eksik olanlar (yazacaklarımız):** iterasyon/token bütçesi (builder'da iterasyon sınırı **yok**),
çalışma alanı, context offload, compaction, subagent delegasyonu.

**Sonuç:** bu bir runtime yazmak değil, **döngüyü alt sınıflayıp dört şeyi eklemek**.

## Kararlar (kullanıcı, 2026-10-03)

1. **Senaryo: araştırma / analiz ajanı.**
2. **Önce P1** (harness); P2/P3 sonra.
3. **Yaklaşım A — advisor tabanlı.** `ToolCallingAdvisor` alt sınıflanır. Reddedilenler aşağıda.
4. **Senkron koşu + sıkı bütçe.** `run()` bloklar; bütçe bunu mümkün kılar.
5. Çalışma alanı v1'de **bellek içi**, koşu kapsamlı.
6. **`execute` (shell) tool'u YOK.**
7. Subagent üçüncü artımda.

## Mimari

### Modül

`zeus-ai-agent`, `zeus-ai`'ye bağımlı, paket `com.zeus.framework.ai.agent`, anahtar
`zeus.ai.agent.enabled`.

**zeus-ai-mcp'den önemli bir fark: harness yeni 3. parti jar GETİRMEZ.** Her şey
`ToolCallingAdvisor` + kendi kodumuz. Dolayısıyla:
- `ZeusCapabilities`'e yeni satır **gerekmez** (yetenek kaydı, paylaşımlı module'deki 3. parti
  autoconfig'leri gatelemek için vardır; yeni paket gelmiyor),
- `zeus-wildfly-module` değişmez, module yenileme ve **WildFly restart gerekmez**,
- opt-in yalnız modülün kendi `@ConditionalOnProperty`'siyle olur.

Bunun bedeli: yetenek kaydı olmadığı için `ZeusCapabilityVerifier`'ın işaretçi-sınıf probe'u da
yoktur — jar'ı ekleyip property'yi yazmayan uygulama sessizce harness'sız kalır (hata almaz).
**Bilinçli kabul**: zeus-ai-mcp'deki durumun tersi, çünkü orada risk "korumasız ağ ucu"ydu;
burada risk yalnızca "özellik çalışmaz" ve bu açılışta değil ilk kullanımda fark edilir.
Dokümana yazılır.

### Uygulamanın gördüğü sözleşme

`ZeusAiAssistant`'ın üslubunu izler: açık varargs tool nesneleri, `run`/`runAs` ikilisi.

```java
public interface ZeusAgent {
    AgentResult<String> run(String task, AgentSpec spec);
    <T> AgentResult<T>  runAs(String task, Class<T> type, AgentSpec spec);
}

public record AgentSpec(
        String systemPrompt,
        List<Object> tools,            // @Tool anotasyonlu nesneler — yeni anotasyon YOK
        AgentBudget budget,
        List<SubAgentSpec> subAgents   // artım 3; v1'de boş liste
) {}

public record AgentResult<T>(
        T output,
        Map<String, String> workspace, // koşu sonunda çalışma alanındaki dosyalar
        AgentRunStats stats
) {}

public record AgentRunStats(
        int steps, long promptTokens, long completionTokens, long durationMs,
        StopReason stopReason, List<String> toolCalls
) {}

public enum StopReason { MODEL_FINISHED, STEP_BUDGET, TOKEN_BUDGET, TIME_BUDGET, ERROR }
```

Üç tasarım kararı burada görünüyor:

- **`tools` yine `@Tool` nesneleri.** Uygulama yeni bir anotasyon öğrenmiyor — MCP turunda
  verdiğimiz kararın aynısı, ve CLAUDE.md'nin "tek Spring AI import istisnası `@Tool`" kuralı
  korunuyor.
- **`workspace` dönüşü.** Araştırma ajanının asıl çıktısı sohbetin son cümlesi değil, **ürettiği
  dosyalardır** (rapor, ara notlar). deepagents'ın en taşınabilir fikri bu.
- **`stats.stopReason`.** Koşunun neden durduğu çağırana açıkça söylenir. Bankada kaçak maliyeti
  görünür kılmanın tek yolu bu; "model bitirdi" ile "bütçe doldu" aynı yanıt gövdesine
  karışmamalı.

### Çalışma alanı

```java
public interface ZeusAgentWorkspace {
    LsResult    ls(String path);
    ReadResult  read(String path, int offset, int limit);
    WriteResult write(String path, String content);
    EditResult  edit(String path, String oldText, String newText);
    GrepResult  grep(String literal, String path);
}
```

**Hata istisna değil dönüş değeridir.** deepagents'ın en net dersi: hatayı okuyan taraf modeldir.
"Dosya yok" bir `ReadResult.error` alanıdır; fırlatılan bir exception modeli döngüden düşürür,
oysa model onu okuyup kendini düzeltebilir. Hata kodları normalize edilir
(`file_not_found`, `invalid_path`, `not_read_before_edit`, `no_match`).

v1 implementasyonu: **`InMemoryWorkspace`, koşu kapsamlı.** Arayüz ileride kalıcı bir backend'i
(Oracle tablosu / disk) kaldıracak şekilde duruyor, ama onu şimdi yazmıyoruz.

Yol kuralları: POSIX benzeri mutlak yollar, `..` ve `~` reddedilir, kök `/`.

### Dosya tool'ları

Tool'lar `ToolContext` üzerinden değil, **koşu başına örneklenerek** çalışma alanına ulaşır:
`ZeusAgent` her koşuda bir `WorkspaceTools(workspace)` nesnesi kurup tool listesine ekler.
deepagents'ın LangGraph state'i (`CONFIG_KEY_READ`/`CONFIG_KEY_SEND` gibi private API'ler)
üzerinden yaptığı şeyi burada tek satırla geçiyoruz — bu, Spring AI'a taşımanın en net kazancı.

**Beş tool:** `ls`, `read_file`, `write_file`, `edit_file`, `grep`.

Bilinçli dışlamalar:
- **`execute` (shell) YOK.** Uygulama sunucusunda kabuk açmak tartışma konusu değil. deepagents'ta
  bu tool sandbox backend'lerine bağlıdır; bizde böyle bir backend yok ve olmayacak.
- `glob` ve `delete`: YAGNI. Gerekirse sonra.

İki ayrıntı birebir kopyalanıyor, çünkü ikisi de ölçülmüş iyi fikir:

1. **Sayfalama başlığı.** `read_file` çıktısı `@@ satır 1-100 / 4213 · sonraki offset 100 @@`
   satırıyla başlar, altındaki her şey birebir dosya içeriğidir. Model sayfalamayı bilerek yapar,
   uydurmaz.
2. **Düzenlemeden önce okuma zorunluluğu.** `edit_file`, dosya o koşuda okunmadıysa hata döner.

**`grep` düz metindir, regex değil** — ve açıklamasında bu açıkça yazar. deepagents'ın bilinçli
kararı: model regex'i yanlış kuruyor ve sessizce boş sonuç alıyor.

> **Tool açıklamaları bu modülün asıl "promptu"dur.** deepagents'ın en şaşırtıcı bulgusu:
> varsayılan sistem promptu **boş** (1 byte); davranış tool açıklamalarında yaşıyor. Bu yüzden
> açıklamalar kod kadar ciddiye alınır ve snapshot testiyle korunur (aşağıda).

### Döngü: `ZeusAgentAdvisor extends ToolCallingAdvisor`

Üç kanca, üç sorumluluk:

**1. Bütçe → `ToolExecutionEligibilityChecker`.** Adım sayısı, toplam token ve duvar saati.
Dolduğunda döngü **temiz durur** (istisna değil) ve `stopReason` bunu söyler. Spring AI'ın
builder'ında iterasyon sınırı **olmadığı için** bu tamamen bizim sorumluluğumuz; deepagents'ın
`recursion_limit: 9_999` varsayılanı bir banka için kabul edilemez.

Varsayılanlar (property ile değiştirilebilir): **15 adım · 3 dakika · 200k token**.

**2. Offload → `doGetNextInstructionsForToolCall`.** Eşiği aşan tool sonucu çalışma alanına
`/tool-results/<toolCallId>.txt` olarak yazılır; sohbete yolu + baş/son birkaç satırı içeren bir
vekil konur ve model `read_file` ile sayfalayarak geri okuyabilir. Bağlam kazancının en büyüğü ve
en az makine gerektireni budur.

Dışlananlar (deepagents'ın listesinden): dosya tool'larının kendi sonuçları offload edilmez —
`read_file` zaten sayfalıyor, `ls`/`grep` kendini kırpıyor; aksi hâlde "oku → offload → tekrar
oku" döngüsü oluşur.

**3. Compaction → `doBeforeCall`.** Bağlam bütçesini aşınca eski mesajlar özetlenir. **Ham geçmiş
silinmez**: çalışma alanına `/conversation/history.md` olarak yazılır ve özet mesajı o yolu
işaret eder. deepagents'ın "compaction yıkıcı bir yeniden yazma değil, bir **olaydır**" fikri;
tekrar oynatma ve hata ayıklama bunu gerektiriyor.

**Ek: sarkan tool çağrısı onarımı.** Koşu başında, yanıtsız kalmış `toolCall` varsa sentetik bir
hata `ToolResponseMessage`'ı üretilir. deepagents'ta 52 satır ve bir sınıf sağlayıcı 400'ünü
önlüyor (kesilmiş/iptal edilmiş çağrılardan sonra geçmiş geçersiz kalıyor).

### Subagent delegasyonu (artım 3)

Mekanizma tek cümlede: **paylaşılan çalışma alanı, yalıtılmış sohbet.**

`task(description, subAgentType)` tool'u, iç içe bir `ChatClient` çağrısı açar:
- **aynı workspace** (paylaşılan disk),
- **taze mesaj geçmişi** — yalnızca `description` bir user mesajı olarak,
- kendi tool'ları (`SubAgentSpec`'te verilmemişse ebeveynin tool'ları),
- geri dönen tek şey: alt ajanın son boş olmayan metni.

Alt ajan `task` tool'unu **almaz** → özyineleme yok. Mevcut alt ajan tipleri `task` tool'unun
**açıklamasında** listelenir (deepagents'ın keşif deseni).

**Bütçe iç içe bölünür:** alt ajan, ebeveynin *kalan* bütçesinden pay alır ve harcadığı
ebeveynden düşülür. Bu bizim eklememizdir; deepagents'ta pratikte sınırsızdır.

Kazanç şu: alt ajanın ara gevezeliği ana bağlama **hiç girmez**; yalnız nihai raporu ve yazdığı
dosyalar kalır.

## Hata yönetimi

- **Tool hataları modele döner** (çalışma alanı hataları zaten dönüş değeri; uygulama tool'larının
  istisnaları Spring AI'ın `ToolExecutionExceptionProcessor`'ı tarafından zaten mesaja çevrilir).
- **Bütçe aşımı istisna değildir**: koşu normal biter, `stopReason` söyler. Çağıran bunu bir hata
  olarak ele almak isterse kendi kararıdır.
- **Model/sağlayıcı hataları** `zeus-ai`'nin mevcut `ZeusAiException` → `502 ProblemDetail`
  yoluna düşer. Harness yeni bir exception hiyerarşisi kurmaz.
- Koşu kısmen ilerleyip hata aldıysa `AgentResult` yine **çalışma alanını taşır** — o ana kadar
  yazılmış raporlar kaybolmaz.

## Test

**En kritik kısıt: döngü gerçek LLM olmadan test edilebilmeli.** Senaryolanmış tool çağrıları
döneren bir `StubChatModel` ile bütçe, offload, compaction ve sarkan-çağrı onarımı deterministik
olarak sınanır. Bu kısıt tasarımı etkiliyor: `ZeusAgentAdvisor` bir `ChatModel` alır, kendi
içinde kurmaz.

| Test | Neyi kilitliyor |
|---|---|
| `InMemoryWorkspaceTest` | Yol kuralları, hata kodları, `edit` eşleşme/çoklu eşleşme |
| `WorkspaceToolsTest` | Sayfalama başlığının biçimi, **okumadan düzenlemenin reddi**, grep'in düz metin olduğu |
| `ToolDescriptionSnapshotTest` | **Tool açıklamalarının golden snapshot'ı** — davranış açıklamalarda yaşadığı için prompt kayması review'da görünür olur. deepagents'ın en değerli test pratiği |
| `BudgetTest` | Üç bütçenin her biri ayrı ayrı durdurur; `stopReason` doğru |
| `OffloadTest` | Eşik üstü sonuç dosyaya gider, vekil mesaj yolu içerir, dosya tool'ları offload edilmez |
| `CompactionTest` | Ham geçmiş `/conversation/history.md`'ye yazılır, özet onu işaret eder |
| `DanglingToolCallTest` | Yanıtsız çağrı sentetik hata mesajıyla kapatılır |
| `ZeusAgentAutoConfigurationTest` | Opt-in simetrisi (`=false` hayatta kalır, property yoksa bean yok) |

Entegrasyon: kanıt uygulamasında `PRODUCT_PKG` tool'larıyla tek bir gerçek koşu.

## Artımlar

| | İçerik | Kapı |
|---|---|---|
| **A1** | Modül + çalışma alanı + 5 tool + `ZeusAgent` sözleşmesi + **bütçe** | Kanıt uygulamasında çok adımlı analiz koşusu; bütçenin gerçekten durdurduğu ölçülür |
| **A2** | Offload + compaction + sarkan çağrı onarımı | Uzun koşuda bağlamın şişmediğinin ölçümü |
| **A3** | Subagent delegasyonu + iç içe bütçe | Paralel alt görev koşusu |

A1 tek başına teslim edilebilir **ve güvenlidir**: bütçesi olan, çalışma alanına rapor yazabilen
bir ajan. Bütçe bilinçli olarak A1'e alındı (önceki taslakta A2'deydi) — en ucuz ve en kritik
güvenlik özelliği, harness'sız bir ajanı bile frenler.

## Kapsam dışı (bilinçli)

- **Kalıcı durum / kesinti / devam.** Senkron koşu kararının doğal sonucu: süreç yeniden
  başlarsa koşu kaybolur. Asenkron iş modeli gerekirse ayrı bir tur.
- **Human-in-the-loop onayı.** Aksiyon alan ajan senaryosu seçilmedi; onay akışı kalıcı duruma
  bağlı, o yüzden onunla birlikte ele alınır.
- **Streaming (SSE).** zeus-ai'de bugün streaming hiç yok; MCP tarafında SSE'den bilinçli
  kaçındık. İlerleme akıtma ayrı bir karar.
- **`execute` / shell**, **sandbox backend'leri**, **harness profilleri** (model başına prompt
  ayarı), **skills / `AGENTS.md` benzeri bellek** — deepagents'ta var, bizde YAGNI.
- **Kalıcı çalışma alanı** (Oracle/disk) — arayüz hazır, implementasyon sonraya.
- **P2 (MCP client)** ve **P3 (RAG)** — ayrı alt projeler.

## Riskler

| # | Risk | Tetik | Yedek |
|---|---|---|---|
| R1 | **Kaçak maliyet.** Ajan döngüsü token yakar; bir hata döngüsü faturayı patlatır | `stats.stopReason` sürekli `STEP_BUDGET`/`TOKEN_BUDGET`; beklenmeyen token artışı | Bütçe A1'de ve varsayılan **sıkı** (15 adım / 3 dk / 200k). Her koşu `AgentRunStats` loglar; audit `zeus-ai-mcp`'deki desenle aynı |
| R2 | **`ToolCallingAdvisor` kancaları değişir.** `protected` API, Spring AI minor sürümünde değişebilir | Sürüm yükseltmesinde derleme hatası ya da testlerin kırmızıya dönmesi | Kancalara dayanan her davranışın `StubChatModel` testi var → kırılma **derleme veya testte** yakalanır, üretimde değil. Uygulama yüzeyi `ZeusAgent` olduğu için churn tek modüle iner |
| R3 | **Senkron koşu HTTP timeout'una takılır** | 3 dk bütçe ile uzun koşu; çağıranın timeout'u daha kısa | Bütçe varsayılanı timeout'un altında tutulur ve dokümanda çağıranın timeout'unu ayarlaması söylenir. Kalıcı çözüm asenkron iş modeli (kapsam dışı) |
| R4 | **Tool açıklaması kayması.** Davranış açıklamalarda yaşadığı için sessiz bir düzenleme ajanı bozar | Snapshot testi kırmızı | `ToolDescriptionSnapshotTest`; açıklama değişikliği bilinçli bir review kararı olur |
| R5 | **Bellek içi çalışma alanı büyür.** Uzun koşuda offload + compaction çıktıları bellekte birikir | Heap baskısı | Çalışma alanına toplam boyut sınırı; aşılırsa yazma hatası döner (model okur ve davranışını değiştirir). Kalıcı backend bu riski devralır |
| R6 | **Yetenek bildirimi unutulur sessiz kalır** (yetenek kaydı olmadığı için verifier uyarmaz) | Bean yok, ilk kullanımda `NoSuchBeanDefinitionException` | Dokümanda açıkça yazılır; gerekirse ileride `ZeusCapabilities`'e 3. parti öneki olmayan bir "yalnız işaretçi" satırı eklenebilir (mekanizma değişikliği, spekülatif olarak alınmaz) |

## Değerlendirilen alternatifler

**B — kendi orkestratörümüz.** Döngüyü sıfırdan yazmak: modeli çağır, tool çağrılarını ayrıştır,
yürüt, tekrarla. Kalıcı durum, kesinti ve insan onayı gerçekten mümkün olurdu. **Reddedildi:**
Spring AI'ın bakımını üstlendiği her şeyi (tool çözümleme, uygunluk denetimi, streaming,
observation) yeniden yazmak ve sürümlerle birlikte bakımını üstlenmek demekti — ve bu, doküman
17'nin *"1.x'te tool döngüsü model implementasyonunun içinde kapalıydı; müdahale ancak
kütüphaneyi fork ederek mümkündü"* diye kaçtığımız duruşa geri dönmek olurdu. 2.0'a geçmemizin
gerekçesi döngünün açılmasıydı; açılan döngüyü kullanmamak o gerekçeyi çürütür.

**C — yalnız çalışma alanı + tool'lar, döngüye dokunma.** En küçük iş. **Reddedildi:** bütçe
olmadan kaçak maliyet riski, compaction olmadan uzun koşuda bağlam taşması — senaryoyu teslim
etmez. (A1 zaten bunun üstüne bütçeyi ekleyen hâlidir.)

**Asenkron iş modeli (başlangıçta).** Uzun araştırma için doğru model. **Ertelendi:** iş deposu,
arka plan yürütücüsü, MDC'nin elle taşınması ve koşu durumunun kalıcılığı kapsamı belirgin şekilde
büyütüyor. Önce senkron + sıkı bütçe ile gerçek iş modeli görülür, sonra karar verilir.

**Subagent'ı tamamen çıkarmak.** Ciddi bir seçenekti. **Tutuldu ama A3'e alındı:** bağlam
yalıtımı araştırma ajanının asıl ölçekleme mekanizması, ama A1/A2 onsuz da teslim edilebilir —
ve gerçek koşularda bağlamın gerçekten yetmediği ölçülünce yazılması daha doğru.
