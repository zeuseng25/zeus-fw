# 15 — zeus-ai (Spring AI 2.x üzerine kurumsal LLM modülü)

`zeus-ai`, framework'ün LLM yeteneği modülüdür. Uygulamalar Spring AI API'sine değil,
tek bir framework sözleşmesine (`ZeusAiAssistant`) bağlanır.

| | |
|---|---|
| artifactId | `com.zeus:zeus-ai` |
| base paket | `com.zeus.framework.ai` |
| Spring AI | **2.0.0** (BOM'dan; `spring-ai.version` property'si) |
| transport | OpenAI-uyumlu `/v1/chat/completions` (vLLM / LiteLLM / OpenRouter / OpenAI) |
| bağımlılık | `spring-ai-starter-model-openai`, `zeus-base`, `spring-boot-autoconfigure` |

## Neden Spring AI 2.x (1.x değil)

Spring AI 1.x **Spring Boot 3.5** hattına bağlıdır ve Boot 4 context'inde yüklenmez; ayrıca
Boot 3.5 ile birlikte **Haziran 2026'da EOL** oldu. Platform zeus 2.0.0 ile Boot 4.0.7'ye
geçtiği için 1.x teknik olarak seçenek değildi. Ayrıntılı gerekçe ve 1.x ↔ 2.x fark tablosu:
`../spring-wildfly-arch/gelistirmeler/17-spring-ai-surum-secimi.md`.

## Neden tek transport: OpenAI-uyumlu endpoint

Kurumda model **vLLM** ile self-host edilir. vLLM, LiteLLM proxy, OpenRouter ve OpenAI'ın
tamamı aynı `/v1/chat/completions` sözleşmesini konuşur. Bu yüzden framework tek starter'a
(`spring-ai-starter-model-openai`) bağlanır; **hedef yalnızca `base-url` ile değişir**:

```properties
# PROD  — kurumsal vLLM
spring.ai.openai.base-url=http://vllm.kurum.local:8000/v1
spring.ai.openai.chat.model=Qwen/Qwen3-30B-A3B-Instruct-2507
spring.ai.openai.api-key=${AI_API_KEY}     # vLLM'de --api-key ile tanımlanan değer

# ARA   — LiteLLM proxy (çok modelli yönlendirme, kota/log)
spring.ai.openai.base-url=http://litellm.kurum.local:4000/v1

# GELİŞTİRME — OpenRouter (vLLM ayağa kalkana kadar; birebir aynı sözleşme)
spring.ai.openai.base-url=https://openrouter.ai/api/v1
spring.ai.openai.chat.model=qwen/qwen3-30b-a3b-instruct-2507
```

Uygulama kodunda **tek satır** değişmez. Model bilinçli olarak açık ağırlıklı seçilmiştir:
geliştirmede OpenRouter üzerinden çağrılan model, üretimde aynı ağırlıklarla vLLM'de host edilir —
davranış farkı en aza iner. Sağlayıcı gerçekten OpenAI-uyumlu olmayan bir şeye dönerse
(ör. Bedrock), uygulama kendi starter'ını ekler; `ZeusAiAssistant` soyutlaması aynen çalışır.

## Sözleşme — `ZeusAiAssistant`

```java
public interface ZeusAiAssistant {
    String ask(String userText, Object... tools);
    <T> T askAs(String userText, Class<T> type, Object... tools);
}
```

Üç yetenek, iki metot:

1. **Sohbet** — `ask("...")` düz metin döner.
2. **Yapılandırılmış çıktı** — `askAs("...", ProductInsight.class)`; JSON şeması tipten türetilir,
   yanıt doğrudan record'a bağlanır. Uygulamada JSON ayrıştırma kodu yoktur.
3. **Tool calling** — her iki metoda `@Tool` anotasyonlu nesneler verilir. Model hangi aracı
   çağıracağına kendisi karar verir; **çağrı döngüsünü Spring AI 2.0'ın `ToolCallingAdvisor`'ı
   yürütür** (1.x'te bu döngü model implementasyonunun içinde gömülüydü ve araya girilemiyordu —
   2.0'ın en önemli mimari değişikliği budur).

## Sınıflar

| Sınıf | Görev |
|-------|-------|
| `ZeusAiAssistant` | Uygulamaların bağlandığı tek arayüz |
| `DefaultZeusAiAssistant` | `ChatClient` tabanlı implementasyon; süre + token loglar, istisnaları sarar |
| `ZeusAiAutoConfiguration` | `ChatClient` (ortak sistem promptu + opsiyonel log advisor) ve assistant bean'i |
| `ZeusAiProperties` | `zeus.ai.*` — `enabled`, `system-prompt`, `log-conversation` |
| `ZeusAiException` | Sağlayıcı/dönüşüm hatalarının tek tipi |
| `ZeusAiExceptionHandler` | `ZeusAiException` → ProblemDetail **502**; ham hata istemciye sızdırılmaz, loga yazılır |

### Auto-configuration ayrıntısı

```java
@AutoConfiguration(afterName = {
        "org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration",
        "org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration" })
```

Sınıf adları **String** olarak verilir; böylece zeus-ai, Spring AI'ın `*-autoconfigure`
paketlerine derleme zamanı bağımlılığı taşımaz (paket/sürüm değişikliğine dayanıklı).
Bean'ler `@ConditionalOnBean(ChatClient.Builder.class)` ile koşulludur: model
yapılandırılmamışsa modül sessizce devre dışı kalır. Tüm modül `zeus.ai.enabled=false` ile
kapatılabilir. `ChatClient` ve `ZeusAiAssistant` `@ConditionalOnMissingBean` ile kayıtlıdır —
uygulama kendi bean'ini tanımlarsa framework geri çekilir.

## `zeus.ai.*` ayarları

| Property | Varsayılan | Açıklama |
|----------|-----------|----------|
| `zeus.ai.enabled` | `true` | Modülü tümüyle kapatır |
| `zeus.ai.system-prompt` | kurumsal Türkçe prompt | Tüm isteklere uygulanan ortak sistem mesajı |
| `zeus.ai.log-conversation` | `false` | İstek/yanıt gövdesini DEBUG'da loglar (`SimpleLoggerAdvisor`). **Üretimde kapalı tutun** — prompt'ta kişisel veri olabilir |

Sağlayıcı ayarları (`base-url`, `api-key`, `chat.model`, `chat.temperature`) framework tarafından
**kopyalanmaz**; Spring AI'ın kendi `spring.ai.openai.*` property'leri kullanılır. Framework
property'lerini ikizlemek, her yeni Spring AI ayarını framework'e taşıma borcu yaratırdı.

## WildFly dağıtımı (ÖNEMLİ)

İnce WAR kuralı burada da geçerlidir:

- `zeus-ai-*.jar` → **WAR içinde** (zeus-* jar'ları WAR'da taşınır).
- `spring-ai-*`, `openai-java-core`, `reactor-*`, `spring-ai-template-st`, `antlr*` →
  **`com.zeus` module'ünde**.

Bu yüzden `zeus-wildfly-module/pom.xml`'e `spring-ai-starter-model-openai` eklendi. Modül
sözleşmesi değiştiği için:

```bash
./scripts/install-zeus-module.sh   # module yeniden üretilir
# ardından WildFly RESTART (main slot güncellendiğinde module tanımı cache'lidir)
```

Ölçüm (bu ekleme ile): `com.zeus` module'ü **157 jar** oldu; uygulama WAR'ı **66 KB**'da kaldı —
Spring AI jar'larının hiçbiri WAR'a girmedi.

### Bu ekleme sırasında düzeltilen iki platform hatası

Spring AI'ın bağımlılık zinciri (reactor-netty, spring-webflux) daha önce hiç karşılaşılmamış
iki durumu ortaya çıkardı — ikisi de deploy'u kıran gerçek hatalardı:

1. **`verify-module-coverage.sh` — classifier'lı artefaktlar.** Script, `dependency:list`
   çıktısındaki sürümü hep 4. alandan okuyordu. Classifier varsa format bir alan uzar
   (`gid:aid:jar:classifier:version:scope`), bu yüzden netty native transport'ları
   (`netty-transport-native-epoll:linux-x86_64` vb.) "module'de yok" diye yanlış raporlandı.
   Script artık alan sayısını sayıp jar adını `aid-version-classifier.jar` olarak kuruyor.

2. **`jakarta.websocket.api` module bağımlılığı.** Module'e `spring-webflux` girince WildFly'ın
   POST_MODULE anotasyon taraması `StandardWebSocketHandlerAdapter`'ı link etmeye çalıştı ve
   `jakarta.websocket.Endpoint` görünmediği için deploy `NoClassDefFoundError` ile düştü.
   `install-zeus-module.sh`'ın ürettiği module.xml'e `jakarta.websocket.api` eklendi.

> **Kural:** kapsam denetimi "jar module'de var mı?" sorusunu cevaplar; "sınıflar link olur mu?"
> sorusunu cevaplayamaz. Module'e yeni bir Spring modülü girdiğinde, o modülün ihtiyaç duyduğu
> **jakarta API module'leri** de module.xml'in `<dependencies>` listesine eklenmelidir.

## Güvenlik notları

- **Anahtar repoya yazılmaz.** `spring.ai.openai.api-key=${AI_API_KEY}` deseni kullanılır;
  değer ortam değişkeninden ya da WildFly `standalone.xml` system-property'sinden gelir.
- **Hata mesajı sızdırılmaz.** Sağlayıcıdan dönen ham hata (URL/anahtar parçası içerebilir)
  loga yazılır, istemciye sabit bir ProblemDetail döner. Doğrulandı: erişilemez endpoint →
  `502 {"title":"AI servisi hatası"}`, ham hata yalnızca logda.
- **Model veritabanına doğrudan erişmez.** Tool'lar uygulamanın repository arayüzünü çağırır;
  modele yalnızca `@Tool` ile açılmış metotlar görünür.
- **Prompt injection**: tool'lar yazma işlemi yapıyorsa (create/update/delete) yetki kontrolü
  tool metodunun İÇİNDE olmalıdır — modelin kararına bırakılmaz. Şu anki örnekte tool'lar
  salt-okunurdur.
- **Maliyet izleme**: her çağrı süre + token (giriş/çıkış/toplam) ile INFO seviyesinde loglanır.

## Kullanım örneği

Uygulama tarafındaki uçtan uca örnek (controller + service + `@Tool` + stored procedure bağlantısı,
canlı çıktılar ve ölçümler): `../spring-wildfly-arch/gelistirmeler/18-spring-ai-entegrasyonu.md`.
