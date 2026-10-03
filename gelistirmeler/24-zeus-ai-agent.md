# 24 — zeus-ai-agent (çok adımlı araştırma ajanı harness'ı)

`zeus-ai-agent`, uygulamaların **çok adımlı, araç çağıra çağıra ilerleyen** ajan koşuları
yazabildiği framework modülüdür (A1 artımı). `zeus-ai`'deki `ZeusAiAssistant` **tek soru — tek
yanıt** içindir; bu modül aynı Spring AI alt yapısını kullanarak bütçeli, çalışma alanlı,
çok adımlı koşuları ekler.

| | |
|---|---|
| artifactId | `com.zeus:zeus-ai-agent` |
| base paket | `com.zeus.framework.ai.agent` |
| yetenek anahtarı | `zeus.ai.agent.enabled` (**varsayılan KAPALI**) — `ZeusCapabilities`'e kayıtlı **DEĞİL**, bkz. aşağıdaki "Bilinen boşluk" |
| bağımlılık | `zeus-ai` (geçişli: `ChatModel`/`ChatClient`/`ToolCallingAdvisor`/`@Tool`), `spring-boot-autoconfigure` |
| yeni 3. parti jar | **YOK** — `zeus-ai`'nin zaten getirdiği Spring AI yığınını aynen kullanır; paylaşımlı `com.zeus` module'ü ve WildFly restart'ı etkilenmez |

## Neden ayrı bir harness — `ZeusAiAssistant` neden yetmiyor

`ZeusAiAssistant.ask(...)` tek bir istek-yanıt turudur: uygulama `@Tool` nesnelerini verir, model
cevap verene kadar Spring AI'ın `ToolCallingAdvisor`'ı döngüyü yürütür, sonuç döner. Bu, "ürünün
açıklamasını yaz" gibi sınırlı görevler için doğrudur.

Araştırma ajanı farklı bir sözleşme ister:
- koşu **kaç adım sürecek önceden bilinmez** — model kendi kararıyla art arda tool çağırır,
- ara bulgular **sohbet bağlamında değil bir çalışma alanında** birikmeli (bağlam patlamasın),
- döngü **sınırsız sürmemeli** — Spring AI'ın `ToolCallingAdvisor`'ında yerleşik bir iterasyon
  sınırı YOKTUR; bütçe olmadan bir hata döngüsü faturayı patlatabilir.

`ZeusAgent`, bu üç ihtiyacı (`ZeusAiAssistant`'a dokunmadan, onun yanında) karşılayan ikinci bir
framework sözleşmesidir.

## `ZeusAgent` sözleşmesi ve örnek kullanım

```java
public interface ZeusAgent {
    AgentResult<String> run(String task, AgentSpec spec);
    <T> AgentResult<T> runAs(String task, Class<T> type, AgentSpec spec);
}
```

- `AgentSpec(systemPrompt, tools, budget)` — `tools` **`ZeusAiAssistant` ile aynı üslup**:
  uygulamanın `@Tool` anotasyonlu nesneleri, varargs yerine `List<Object>`. Uygulama yeni bir
  anotasyon öğrenmez.
- `AgentResult<T>(output, workspace, stats)` — `output` modelin son çıktısı (`run` için metin,
  `runAs` için hedef tipe dönüştürülmüş kayıt/POJO); `workspace`, koşu sonunda çalışma alanındaki
  **tüm dosyalar**; `stats` adım/token/süre + `stopReason`.

```java
AgentResult<String> sonuc = zeusAgent.run(
        "PRODUCT_PKG'deki tüm ürünleri incele, stok riski taşıyanları /rapor.md'ye yaz.",
        AgentSpec.of("Sen bir envanter analistisin.", productAiTools));

String rapor = sonuc.workspace().get("/rapor.md");   // koşu hatayla bitse bile burada
```

Enjeksiyon noktası otomatik kurulan `ZeusAgent` bean'idir (`@RequiredArgsConstructor` ile diğer
framework sözleşmeleri gibi).

## Çalışma alanı ve beş tool — `execute` neden yok

`ZeusAgentWorkspace` (impl: `InMemoryWorkspace`), koşu kapsamlı bir sanal dosya sistemidir; ara
bulgular ve nihai rapor buraya yazılır, sohbete uzun metin dökülmez. `WorkspaceTools` bu çalışma
alanını modele **beş** `@Tool` olarak açar: `ls`, `readFile`, `writeFile`, `editFile`, `grep`.
"Okumadan düzenleme yok" kuralı `WorkspaceTools`'ta tutulur (depoda değil) — kural modelin
davranışıyla ilgilidir.

**Fix (I6):** bu kural NORMALİZE EDİLMİŞ yollarla takip edilir, çağıranın verdiği ham yolla DEĞİL.
`InMemoryWorkspace.normalize`, `//`'yi tek `/`'ye indirir ve sondaki `/`'yi atar; `okunanlar`
kümesi önceden ham yolu tutuyordu, bu yüzden `readFile("/a//b.md")` sonrası `editFile("/a/b.md")`
— dosya gerçekten okunmuş olsa da — REDDEDİLİYORDU. `writeFile` de aynı hatayı taşıyordu: ham
yolu kaydedip normalize edilmiş yolu DÖNDÜRÜYORDU. Fix: `writeFile` artık `r.path()`'i (workspace'in
döndürdüğü normalize yol) kaydeder; `readFile` için `ReadResult`'a bir `path` alanı eklendi ve
`WorkspaceTools` onu kullanır. `WorkspaceToolsTest`'teki `kanonikOlmayanYolla_*` testleri her iki
akışı (oku-sonra-düzenle, yaz-sonra-düzenle) kilitler.

**`execute` / shell bilinçli olarak YOK** (tasarım dokümanının "kapsam dışı" kararı,
`docs/superpowers/specs/2026-10-03-zeus-ai-agent-harness-design.md`): bu modül bir **araştırma**
ajanı harness'ıdır, aksiyon alan/komut çalıştıran bir ajan değil. Shell eklemek sandbox, onay akışı
ve kalıcı durum gibi tamamen ayrı bir risk yüzeyi açardı — deepagents'ta var, burada YAGNI.
Aynı gerekçeyle **kalıcı durum/kesinti/devam**, **human-in-the-loop onayı** ve **streaming** de
A1 kapsamı dışındadır.

### Tool açıklamaları bu modülün PROMPTUDUR

deepagents'ın ölçülmüş bulgusu: varsayılan sistem promptu **boştur**; ajan davranışı tool
açıklamalarında yaşar (sayfalama biçimi, "önce oku", "düz metin arama" gibi kurallar). Bu yüzden
bir açıklamayı sessizce düzenlemek ajanın davranışını değiştirir — kod değişikliği kadar ciddiye
alınmalıdır. `ToolDescriptionSnapshotTest` açıklamaların golden snapshot'ını kilitler; bilinçli bir
değişiklikte test burada güncellenir, sessizce değil.

## Bütçe — `AgentBudget` / `BudgetEligibilityChecker` / `StopReason`

Spring AI'ın `ToolCallingAdvisor.Builder`'ında döngüyü durduracak **yerleşik bir iterasyon sınırı
yoktur**; müdahale edilebilen tek genişletme noktası `ToolExecutionEligibilityChecker`'dır.
`BudgetEligibilityChecker` bunu uygular ve üç sınırı aynı anda izler:

```java
public record AgentBudget(int maxSteps, long maxTokens, Duration maxDuration) {
    public static AgentBudget defaults() {   // 15 adım · 200.000 token · 3 dakika
        return new AgentBudget(15, 200_000L, Duration.ofMinutes(3));
    }
}
```

**Varsayılanlar bilinçli olarak SIKIDIR.** En ucuz ve en kritik güvenlik özelliği budur — harness
olmayan bir ajanı bile frenler; bütçe tasarımın bir sonraki artımına (`A2`) değil, A1'in kendisine
dahil edildi. Uzun koşular için çağıran bunları **açıkça** yükseltmelidir
(`zeus.ai.agent.max-steps` / `max-tokens` / `max-duration`).

Bütçe dolduğunda döngü **temiz durur** — istisna fırlatılmaz, çünkü o ana kadar üretilmiş rapor ve
çalışma alanı korunmalıdır. Durma sebebi `StopReason` ile `AgentRunStats`'a taşınır:

| `StopReason` | Anlamı |
|---|---|
| `MODEL_FINISHED` | Model araç çağırmayı bıraktı — normal bitiş |
| `STEP_BUDGET` / `TOKEN_BUDGET` / `TIME_BUDGET` | İlgili sınır aşıldı |
| `ERROR` | Koşu istisna ile bitti (çalışma alanı yine de döner) |

`stopReason` bilinçli olarak sonucun bir parçasıdır: "model bitirdi" ile "bütçe doldu" aynı yanıt
gövdesine karışmaz — kurumsal maliyet görünürlüğünün tek yolu budur (`zeus-ai-mcp`'deki audit
desenine paralel). **`runAs`'ta da aynı ayrım geçerlidir** (fix): bütçe tam kesme anında durunca
`entity(type)` boş/eksik içerik üstünde istisna fırlatabilir; `DefaultZeusAgent`'ın yakala-bloğu
bu durumda `stopReason`'ı `ERROR` ile EZMEZ — `StopReason` zaten `STEP_BUDGET`/`TOKEN_BUDGET`/
`TIME_BUDGET`'ten biriyse o korunur, `ERROR` yalnızca bütçe SEBEP DEĞİLKEN yazılır
(`DefaultZeusAgentTest.runAsButceDolunca_ERRORDegilButceSebebiKorunur`).

`AgentRunStats`, token sayısını `promptTokens`/`completionTokens` olarak AYRI tutar
(`Usage.getPromptTokens()`/`getCompletionTokens()`) — kurumsal maliyet raporlaması ikisini farklı
fiyatlandırır. `tokens()` ikisinin toplamı olarak kısa yoldan kalır; bütçe denetimi zaten bu
toplama karşı çalışır. `toolCalls` (tasarım dokümanındaki sözleşmede var) **A1'de BİLİNÇLİ olarak
YOK** — tool-çağrısı kaydını doğru tutmak A2'nin offload mekanizmasıyla aynı dekoratörü
gerektiriyor, iki kez yazılmasın diye A2'ye ertelendi (bkz. tasarım dokümanı, "stats sözleşmesi"
notu).

### Bütçe property'leri artık GERÇEKTEN uygulanıyor (C1 fix)

**Önceden ÖLÇÜLEN kusur:** `AgentSpec.of(...)` her zaman `AgentBudget.defaults()` (15 adım / 3 dk
/ 200k token) ile DOLDURULUYORDU ve `ZeusAgentAutoConfiguration`, `DefaultZeusAgent`'a HİÇBİR
bütçe geçirmiyordu — sonuç: `zeus.ai.agent.max-steps`/`max-tokens`/`max-duration` property'leri
**ÖLÜ KODDU**, `ZeusAgentProperties.toBudget()`'ı çağıran tek yer bir testti.

**Fix:**
- `AgentSpec`'in compact constructor'ı artık `null` bütçeyi `AgentBudget.defaults()`'a
  DÜŞÜRMEZ — `null`, "ajanın yapılandırılmış varsayılanını kullan" anlamına gelir.
  `AgentSpec.of(...)` bu yüzden `budget = null` döner.
- `DefaultZeusAgent`, kurucusunda bir `AgentBudget defaultBudget` ALIR (tek kurucu); her koşuda
  etkin bütçeyi `spec.budget() != null ? spec.budget() : defaultBudget` ile çözer.
- `ZeusAgentAutoConfiguration`, `ZeusAgent` bean'ini kurarken `properties.toBudget()`'ı bu
  parametreye geçirir. **Minor fix:** "modül yüklendi" logu artık autoconfig'in kurucusunda
  DEĞİL, `@Bean` metodunda basılır — eskiden `ChatModel` bean'i hiç yokken de basılıyordu (yani
  `ZeusAgent` hiç kurulmasa da "yüklendi" diyordu) ve C1 öncesi hep sabit 15/200k/3dk yazıyordu;
  artık yalnız bean GERÇEKTEN kurulunca ve GERÇEKTEN uygulanacak bütçeyle basılır.
- Testler bunu uçtan uca kanıtlar: `DefaultZeusAgentTest.yapilandirilmisVarsayilanButceGercektenUygulanir`
  küçük bir yapılandırılmış varsayılanla kurulan bir ajanın 15 adımda değil o küçük sınırda
  durduğunu gösterir; `specTeAcikcaVerilenButceAjaninVarsayilaniniEZER` ise `spec.budget()`
  AÇIKÇA verildiğinde onun önceliğini doğrular.

## Koşu kapsamlı kurulumun gerekçesi

`DefaultZeusAgent.run`/`runAs` her çağrıda **taze** bir dünya kurar: yeni `InMemoryWorkspace`,
yeni `WorkspaceTools`, yeni `BudgetEligibilityChecker`, yeni `ChatClient`. deepagents'ın LangGraph
state kanallarıyla (ve private API'leriyle) çözdüğü şey burada **nesne ömrüyle** çözülür. Bunun
somut faydası: bütçe sayacının koşular arasında sızması YAPISAL olarak imkânsızdır — paylaşılan bir
`BudgetEligibilityChecker` örneği olsaydı bir önceki koşunun adım/token sayacı bir sonrakine
devrederdi. `defaultBudget` (bkz. yukarıdaki C1 fix) bu kuralı BOZMAZ: o alan koşu BAŞLANGICINDA
okunan sabit bir yapılandırmadır, koşu sırasında yazılmaz; durum tutan tek şey hâlâ koşuya özel
`BudgetEligibilityChecker`'dır.

**Test düzeltmesi (I4):** bu iddianın testi (`DefaultZeusAgentTest.kosularArasindaCalismaAlaniPAYLASILMAZ`)
önceden ikinci koşu için YENİ bir `DefaultZeusAgent` örneği kuruyordu — bu, çalışma alanını/bütçe
sayacını bean üstünde yanlışlıkla TUTAN bir implementasyonu da yeşil geçirirdi. Artık test AYNI
ajan örneğini iki kez çalıştırır ve ikinci koşunun hem çalışma alanının boş hem adım sayacının
sıfırdan başladığını doğrular.

## Platform gerçeği — `ToolCallingChatOptions` olmadan döngü hiç çalışmaz

**Ölçülmüş bulgu** (Task 4, `StubChatModel` ile): Spring AI'ın `ToolCallingAdvisor.adviseCall`'ı,
prompt'un `ChatOptions`'ı bir `ToolCallingChatOptions` **DEĞİLSE** tüm tool döngüsünü (ve
dolayısıyla bizim bütçe checker'ımızı) hiç devreye **sokmaz** — çağrı doğrudan alttaki modele
geçer. `ChatModel.getOptions()`'ın varsayılan dönüşü düz `ChatOptions`'tır; bu da döngüyü sessizce
devre dışı bırakır.

Bu, bugün **üretimde güvenli** bir varsayımdır çünkü `OpenAiChatOptions implements
ToolCallingChatOptions` ve `zeus-ai` OpenAI-uyumlu starter'a bağlıdır (vLLM/LiteLLM/OpenRouter) —
her ikisi de doğrulandı. **Ama ileride farklı bir model sağlayıcısı eklenirse kontrol edilecek
İLK şey budur:** sağlayıcının `ChatModel`'i `ToolCallingChatOptions` döndürmüyorsa, ajan hiçbir
hata vermeden hiçbir tool çağırmaz — sessiz bir arıza modudur. `StubChatModel`'in testleri de tam
bu yüzden `getOptions()`'ı override eder; override OLMADAN tüm senaryolarda adım/token sıfırda
kalır ve `STEP_BUDGET` hiç tetiklenmez (bkz. `task-4-report.md`).

## Senkron koşu sınırı ve HTTP timeout notu

`ZeusAgent.run`/`runAs` **SENKRONDUR**: çağrı bloklar. Bunu güvenli kılan şey sıkı bütçedir
(varsayılan 15 adım / 3 dakika). Uzun koşuları bir HTTP isteğinin arkasına koyan uygulama,
**çağıranın HTTP timeout'unu bütçenin `maxDuration`'ından BÜYÜK** tutmalıdır — aksi hâlde istemci,
ajan kendi bütçesiyle temiz durmadan önce bağlantıyı keser ve çalışma alanındaki rapor hiç
dönemez. Kalıcı çözüm (asenkron iş modeli: iş deposu + arka plan yürütücüsü + koşu durumunun
kalıcılığı) bilinçli olarak A1 kapsamı dışıdır.

## ⚠️ Bilinen boşluk — yetenek kaydı yok

Bu modül **`ZeusCapabilities`'e kayıtlı DEĞİLDİR**. Diğer yeteneklerin (`ai`, `ai-mcp`, `database`,
`soap`) hepsi paylaşımlı `com.zeus` module'üne **yeni bir 3. parti autoconfig paketi** getirdiği
için `ZeusAutoConfigurationFilter`'ın gateleyeceği bir şey vardı; `zeus-ai-agent` ise `zeus-ai`'nin
zaten getirdiği Spring AI sınıflarını kullanır, **yeni bir paket getirmez**. Gatelenecek bir 3.
parti autoconfig olmadığı için kayda alınacak bir şey de yoktur; opt-in'i tek başına modülün
kendi `@ConditionalOnProperty(prefix = "zeus.ai.agent", name = "enabled", havingValue = "true")`'ı
sağlar.

**Bedeli gerçektir ve `zeus-ai-mcp`'dekinden farklıdır.** `zeus-ai-mcp`'de eşdeğer risk
"korumasız bir ağ ucu"ydu (ağır, build tarafında `test-mcp-opt-in-butunlugu.sh` ile fail-closed
denetlenir). Burada risk yalnızca **"özellik çalışmıyor"**: bir uygulama `zeus-ai-agent` jar'ını
pom'una ekleyip `zeus.ai.agent.enabled=true` yazmayı **unutursa**, açılışta hiçbir uyarı
**ALMAZ** — `ZeusCapabilityVerifier`'ın denetlediği "bağımlılık var, property yok" çelişkisi bu
modül için tanımsızdır, çünkü denetleyici yalnız kayıtlı yetenekleri bilir. Sonuç: `ZeusAgent`
bean'i sessizce kurulmaz ve hata ilk kullanım anında `NoSuchBeanDefinitionException` olarak
yüzeye çıkar (tasarım dokümanının R6 riski). Mekanizma değişikliği (ör. `ZeusCapabilities`'e 3.
parti öneki olmayan "yalnız işaretçi" bir satır eklemek) ileride mümkündür ama A1'de spekülatif
olarak alınmadı.

## Testler

`zeus-ai-agent/src/test` — 50 test:

| Sınıf | Neyi kilitliyor |
|---|---|
| `InMemoryWorkspaceTest` | Yol kuralları, hata kodları, `edit` eşleşme/çoklu eşleşme, sayfalama |
| `WorkspaceToolsTest` | Sayfalama başlığı biçimi, okumadan düzenlemenin reddi, grep'in düz metin olduğu, **kanonik olmayan yolla oku/yaz-sonra-düzenle (I6)** |
| `ToolDescriptionSnapshotTest` | Beş tool'un açıklamalarının golden snapshot'ı |
| `BudgetEligibilityCheckerTest` | Üç bütçenin her biri ayrı ayrı durdurur; `stopReason` doğru |
| `DefaultZeusAgentTest` | `StubChatModel` ile uçtan uca koşu — araç çağırma, bütçe dolması, hata ile bitiş, çalışma alanının korunması; **yapılandırılmış varsayılan bütçenin gerçekten uygulandığı ve `spec.budget()`'ın onu ezebildiği (C1)**; **`runAs` mutlu yol + bütçe dolunca `ERROR` değil bütçe sebebinin korunduğu (I1)**; **aynı ajan örneğiyle iki koşu — çalışma alanı VE adım sayacı sızmıyor (I4)**; **kısmi başarısız koşuda çalışma alanının korunduğu, istisnanın sızmadığı (I5)** |
| `ZeusAgentAutoConfigurationTest` | **Opt-in simetrisi**: `=false` açılışı çökertmez, property yoksa bean yok, `true` olunca `ZeusAgent` kurulur, `ChatModel` yoksa sessizce kurulmaz, bütçe varsayılanları property ile değiştirilebilir |

## Sırada ne var

- **A2** — offload + compaction + sarkan (dangling) tool call onarımı: uzun koşuda bağlamın
  şişmediğinin ölçümü. Büyük tool sonuçları çalışma alanına offload edilir, ham geçmiş
  `/conversation/history.md`'ye yazılıp özetlenir.
- **A3** — subagent delegasyonu + iç içe bütçe: paralel alt görev koşusu, bağlam yalıtımı.
- **Task 6** (bu planın bir sonraki adımı): kanıt uygulamasında (`spring-wildfly-arch`)
  `PRODUCT_PKG` tool'larıyla gerçek, çok adımlı bir koşu — bütçenin gerçekten durduğunun uçtan uca
  ölçümü.
