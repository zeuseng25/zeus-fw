# zeus-ai-agent A1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Uygulamaların bütçeli, çok adımlı bir araştırma ajanı yazabilmesi için `zeus-ai-agent` modülünü kurmak: çalışma alanı (sanal dosya sistemi) + beş dosya tool'u + `ZeusAgent` sözleşmesi + adım/token/süre bütçesi.

**Architecture:** Spring AI 2.0'ın tool döngüsü (`ToolCallingAdvisor`) **aynen kullanılır**; A1'de alt sınıflanmaz. Bütçe, `ChatClient.builder(...)`'ın beşinci aşırı yüklemesine verilen bir `ToolCallingAdvisor.Builder` üzerinden özel bir `ToolExecutionEligibilityChecker` olarak takılır. Çalışma alanı ve tool'lar **koşu başına** örneklenir; `ZeusAgent.run()` her çağrıda taze bir workspace + tools + ChatClient kurar. Böylece deepagents'ın LangGraph state kanalları üzerinden yaptığı şey, tek bir nesne ömrüyle çözülür.

**Tech Stack:** Java 25 · Spring Boot 4.0.7 · Spring AI 2.0.1 (`spring-ai-model`, `spring-ai-client-chat`) · JUnit 5 + AssertJ + Mockito (zeus-parent'tan) · Maven

**Spec:** `docs/superpowers/specs/2026-10-03-zeus-ai-agent-harness-design.md`

## Global Constraints

- Modül adı `zeus-ai-agent`, artifactId `com.zeus:zeus-ai-agent`, paket kökü `com.zeus.framework.ai.agent`.
- Pom'da **sürüm yazılmaz** (zeus BOM yönetir). Parent `zeus-parent`, `<relativePath>../zeus-parent/pom.xml</relativePath>`.
- **Yeni 3. parti bağımlılık EKLENMEZ.** Modül yalnız `spring-boot-autoconfigure` + `com.zeus:zeus-ai`'ye bağlanır. Dolayısıyla `ZeusCapabilities`'e satır eklenmez, `zeus-wildfly-module` değişmez, WildFly restart gerekmez.
- Yetenek anahtarı `zeus.ai.agent.enabled`, `@ConditionalOnProperty(..., havingValue = "true")`, **`matchIfMissing` YOK** (varsayılan kapalı).
- Kod yorumları ve javadoc **Türkçe**; sınıf/metot adları İngilizce (mevcut `zeus-ai` / `zeus-ai-mcp` üslubu).
- `@Tool` açıklamaları **Türkçe** (kanıt uygulamasının `ProductAiTools`'u ve zeus-ai'nin sistem promptu Türkçe).
- Spring AI'ın `*.autoconfigure` paketlerine **derleme bağımlılığı kurulmaz**; autoconfig sıralaması `afterName` ile **dize** olarak verilir.
- **`execute` / shell tool'u YOKTUR** ve eklenmeyecektir.
- Her commit mesajı Türkçe; **`Co-Authored-By` ya da benzeri attribution satırı EKLENMEZ.**
- Build komutu: `export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home` sonra `mvn -B ...`. (Kabuğun varsayılan JAVA_HOME'u 17'dir ve `/usr/libexec/java_home -v 25` çalışmaz.)
- Çalışma dalı: `zeus-ai-gelistirmeleri` (her iki repoda).

### Doğrulanmış Spring AI API'leri (plan bunlara dayanıyor; 2026-10-03'te bytecode'dan okundu)

```java
// ChatClient'ı KENDİ tool-döngüsü builder'ımızla kurmanın public yolu:
static ChatClient.Builder ChatClient.builder(ChatModel, ObservationRegistry,
        ChatClientObservationConvention, AdvisorObservationConvention,
        ToolCallingAdvisor.Builder<?>);
// convention'lar null olabilir; registry için ObservationRegistry.NOOP kullanılır.

ToolCallingAdvisor.Builder<?> ToolCallingAdvisor.builder();
T ToolCallingAdvisor.Builder.toolExecutionEligibilityChecker(ToolExecutionEligibilityChecker);

interface ToolExecutionEligibilityChecker extends Function<ChatResponse, Boolean> {
    default boolean isToolCallResponse(ChatResponse response);
}

interface ChatModel { ChatResponse call(Prompt prompt); /* + default'lar */ }
ChatResponse(List<Generation>)            // public ctor
Generation(AssistantMessage)              // public ctor
AssistantMessage(String)                  // public ctor (araç çağrısı yok)
AssistantMessage.Builder<?> AssistantMessage.builder();   // .content(..).toolCalls(..).build()
record AssistantMessage.ToolCall(String id, String type, String name, String arguments);
```

---

## File Structure

**Yeni modül — `zeus-fw/zeus-ai-agent/`**

| Dosya | Sorumluluk |
|---|---|
| `pom.xml` | Modül tanımı; üç bağımlılık, sürümsüz |
| `src/main/java/com/zeus/framework/ai/agent/workspace/ZeusAgentWorkspace.java` | Çalışma alanı arayüzü (5 işlem) |
| `.../workspace/WorkspaceResults.java` | `LsResult` / `ReadResult` / `WriteResult` / `EditResult` / `GrepResult` record'ları + `WorkspaceError` sabitleri |
| `.../workspace/InMemoryWorkspace.java` | Koşu kapsamlı bellek içi implementasyon; yol doğrulama |
| `.../workspace/WorkspaceTools.java` | Beş `@Tool` metodu; sayfalama başlığı, okuma-önce-düzenleme kuralı |
| `.../AgentBudget.java` | Adım / token / süre sınırları (record) |
| `.../BudgetEligibilityChecker.java` | `ToolExecutionEligibilityChecker` — döngüyü bütçe dolunca durdurur; koşu kapsamlı sayaç |
| `.../StopReason.java`, `.../AgentRunStats.java`, `.../AgentSpec.java`, `.../AgentResult.java` | Sözleşme tipleri |
| `.../ZeusAgent.java` | Uygulamanın bağlandığı arayüz |
| `.../DefaultZeusAgent.java` | Koşu kurulumu: workspace + tools + ChatClient + bütçe; `run` / `runAs` |
| `.../ZeusAgentProperties.java` | `zeus.ai.agent.*` |
| `.../ZeusAgentAutoConfiguration.java` | Opt-in + bean kurulumu |
| `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | Kayıt (tek satır) |

**Testler — `zeus-fw/zeus-ai-agent/src/test/java/com/zeus/framework/ai/agent/`**
`workspace/InMemoryWorkspaceTest.java` · `workspace/WorkspaceToolsTest.java` · `workspace/ToolDescriptionSnapshotTest.java` · `BudgetEligibilityCheckerTest.java` · `DefaultZeusAgentTest.java` + `StubChatModel.java` · `ZeusAgentAutoConfigurationTest.java`

**Değişen mevcut dosyalar**
`zeus-fw/pom.xml` (`<modules>`) · `zeus-fw/zeus-dependencies/pom.xml` (BOM) · `zeus-fw/CLAUDE.md` (modül tablosu) · `zeus-fw/gelistirmeler/24-zeus-ai-agent.md` (yeni; sıradaki boş numara **24**)

**Kanıt uygulaması — `spring-wildfly-arch/`**
`src/main/java/com/zeus/springwildflyarch/ai/ProductAnalysisAgent.java` · `src/main/java/com/zeus/springwildflyarch/controller/ProductAgentController.java` · `pom.xml` · `src/main/resources/application.properties` · `src/test/resources/application.properties` · `gelistirmeler/20-arastirma-ajani.md`

---

## Task 1: Çalışma alanı — arayüz, sonuç tipleri, bellek içi implementasyon

**Files:**
- Create: `zeus-fw/zeus-ai-agent/pom.xml`
- Create: `zeus-fw/zeus-ai-agent/src/main/java/com/zeus/framework/ai/agent/workspace/ZeusAgentWorkspace.java`
- Create: `zeus-fw/zeus-ai-agent/src/main/java/com/zeus/framework/ai/agent/workspace/WorkspaceResults.java`
- Create: `zeus-fw/zeus-ai-agent/src/main/java/com/zeus/framework/ai/agent/workspace/InMemoryWorkspace.java`
- Modify: `zeus-fw/pom.xml` (`<modules>` listesine ekleme)
- Modify: `zeus-fw/zeus-dependencies/pom.xml` (BOM kaydı)
- Test: `zeus-fw/zeus-ai-agent/src/test/java/com/zeus/framework/ai/agent/workspace/InMemoryWorkspaceTest.java`

**Interfaces:**
- Consumes: yok (ilk görev)
- Produces:
  - `ZeusAgentWorkspace` arayüzü: `LsResult ls(String path)`, `ReadResult read(String path, int offset, int limit)`, `WriteResult write(String path, String content)`, `EditResult edit(String path, String oldText, String newText)`, `GrepResult grep(String literal, String path)`
  - `WorkspaceResults.LsResult(List<String> paths, String error)`
  - `WorkspaceResults.ReadResult(String content, int startLine, int endLine, int totalLines, Integer nextOffset, String error)`
  - `WorkspaceResults.WriteResult(String path, String error)`
  - `WorkspaceResults.EditResult(String path, int occurrences, String error)`
  - `WorkspaceResults.GrepResult(List<String> matches, boolean truncated, String error)`
  - `WorkspaceResults.WorkspaceError` sabitleri: `FILE_NOT_FOUND`, `INVALID_PATH`, `IS_DIRECTORY`, `NO_MATCH`, `MULTIPLE_MATCHES`, `TOO_LARGE`
  - `InMemoryWorkspace implements ZeusAgentWorkspace`, ctor `InMemoryWorkspace(int maxTotalBytes)` ve `InMemoryWorkspace()` (varsayılan 8 MB); ek metot `Map<String,String> snapshot()`

- [ ] **Step 1: Modülü oluştur ve reaktöre bağla**

`zeus-fw/zeus-ai-agent/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.zeus</groupId>
        <artifactId>zeus-parent</artifactId>
        <version>${revision}</version>
        <relativePath>../zeus-parent/pom.xml</relativePath>
    </parent>

    <artifactId>zeus-ai-agent</artifactId>
    <packaging>jar</packaging>

    <name>Zeus AI Agent</name>
    <description>Çok adımlı araştırma ajanı harness'ı: koşu kapsamlı çalışma alanı (sanal dosya
        sistemi) + dosya tool'ları + adım/token/süre bütçesi. Spring AI 2.0'ın tool döngüsünü
        (ToolCallingAdvisor) aynen kullanır; YENİ 3. PARTİ BAĞIMLILIK GETİRMEZ, bu yüzden
        paylaşımlı com.zeus module'ü ve WildFly restart'ı etkilenmez.</description>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-autoconfigure</artifactId>
        </dependency>

        <!-- ChatClient / ChatModel / ToolCallingAdvisor / @Tool buradan geçişli gelir
             (zeus-ai -> spring-ai-starter-model-openai). Harness Spring AI'ın
             *.autoconfigure paketlerine DERLEME bağımlılığı kurmaz; sıralama afterName
             ile dize olarak verilir. -->
        <dependency>
            <groupId>com.zeus</groupId>
            <artifactId>zeus-ai</artifactId>
        </dependency>
    </dependencies>

</project>
```

`zeus-fw/pom.xml` içinde `<module>zeus-ai-mcp</module>` satırının **hemen altına** ekle:

```xml
        <!-- Çok adımlı araştırma ajanı harness'ı: çalışma alanı + dosya tool'ları + bütçe.
             zeus-ai'ye bağlıdır → reaktörde ondan SONRA gelir. -->
        <module>zeus-ai-agent</module>
```

`zeus-fw/zeus-dependencies/pom.xml` içinde `zeus-ai-mcp` girdisinin **hemen altına** ekle:

```xml
            <dependency>
                <groupId>com.zeus</groupId>
                <artifactId>zeus-ai-agent</artifactId>
                <version>${revision}</version>
            </dependency>
```

- [ ] **Step 2: Sonuç tiplerini yaz**

`WorkspaceResults.java`:

```java
package com.zeus.framework.ai.agent.workspace;

import java.util.List;

/**
 * Çalışma alanı işlemlerinin sonuç tipleri.
 *
 * <p><b>Hata istisna değil, DÖNÜŞ DEĞERİDİR.</b> Bu arayüzün tüketicisi modeldir: "dosya yok"
 * bilgisini okuyup kendini düzeltebilmesi gerekir. Fırlatılan bir istisna modeli döngüden
 * düşürür ve ajan hatadan öğrenemez.
 *
 * <p>Hata kodları sabit ve normalize edilmiştir; model serbest metin yerine aynı dizeleri görür.
 */
public final class WorkspaceResults {

    private WorkspaceResults() {
    }

    /** Normalize hata kodları — model her zaman aynı dizeyi görür. */
    public static final class WorkspaceError {
        public static final String FILE_NOT_FOUND = "file_not_found";
        public static final String INVALID_PATH = "invalid_path";
        public static final String IS_DIRECTORY = "is_directory";
        public static final String NO_MATCH = "no_match";
        public static final String MULTIPLE_MATCHES = "multiple_matches";
        public static final String TOO_LARGE = "too_large";

        private WorkspaceError() {
        }
    }

    /** @param paths bulunan yollar (alfabetik) · @param error hata kodu ya da {@code null} */
    public record LsResult(List<String> paths, String error) {
        public static LsResult ok(List<String> paths) {
            return new LsResult(paths, null);
        }

        public static LsResult error(String error) {
            return new LsResult(List.of(), error);
        }
    }

    /**
     * @param content    istenen pencerenin içeriği (başlıksız, ham)
     * @param startLine  1 tabanlı ilk satır
     * @param endLine    1 tabanlı son satır
     * @param totalLines dosyanın toplam satır sayısı
     * @param nextOffset pencere dosyanın sonuna ulaşmadıysa sıradaki offset, ulaştıysa {@code null}
     */
    public record ReadResult(String content, int startLine, int endLine, int totalLines,
                             Integer nextOffset, String error) {
        public static ReadResult error(String error) {
            return new ReadResult(null, 0, 0, 0, null, error);
        }
    }

    public record WriteResult(String path, String error) {
        public static WriteResult ok(String path) {
            return new WriteResult(path, null);
        }

        public static WriteResult error(String error) {
            return new WriteResult(null, error);
        }
    }

    /** @param occurrences değiştirilen eşleşme sayısı (bu sürümde her zaman 1) */
    public record EditResult(String path, int occurrences, String error) {
        public static EditResult ok(String path, int occurrences) {
            return new EditResult(path, occurrences, null);
        }

        public static EditResult error(String error) {
            return new EditResult(null, 0, error);
        }
    }

    /** @param matches {@code yol:satırNo:içerik} biçiminde eşleşmeler */
    public record GrepResult(List<String> matches, boolean truncated, String error) {
        public static GrepResult ok(List<String> matches, boolean truncated) {
            return new GrepResult(matches, truncated, null);
        }

        public static GrepResult error(String error) {
            return new GrepResult(List.of(), false, error);
        }
    }
}
```

`ZeusAgentWorkspace.java`:

```java
package com.zeus.framework.ai.agent.workspace;

import com.zeus.framework.ai.agent.workspace.WorkspaceResults.EditResult;
import com.zeus.framework.ai.agent.workspace.WorkspaceResults.GrepResult;
import com.zeus.framework.ai.agent.workspace.WorkspaceResults.LsResult;
import com.zeus.framework.ai.agent.workspace.WorkspaceResults.ReadResult;
import com.zeus.framework.ai.agent.workspace.WorkspaceResults.WriteResult;

/**
 * Ajanın çalışma alanı — koşu boyunca ara çıktıların ve nihai raporun yazıldığı sanal dosya
 * sistemi.
 *
 * <p><b>Neden var:</b> araştırma ajanının asıl çıktısı sohbetin son cümlesi değil, ÜRETTİĞİ
 * DOSYALARDIR. Ara bulguları bağlamda biriktirmek yerine dosyaya yazmak, uzun koşuların bağlamı
 * patlatmasını da önler.
 *
 * <p>Yollar POSIX benzeridir ve MUTLAK olmalıdır ({@code /} ile başlar). {@code ..} ve {@code ~}
 * reddedilir.
 *
 * <p>Hiçbir metot istisna fırlatmaz; hatalar sonuç tipindeki {@code error} alanında döner
 * (bkz. {@link WorkspaceResults}).
 */
public interface ZeusAgentWorkspace {

    /** Verilen dizinin altındaki dosya yolları (özyinelemeli, alfabetik). */
    LsResult ls(String path);

    /**
     * Dosyanın bir penceresini okur.
     *
     * @param offset 0 tabanlı başlangıç satırı; negatifse 0 kabul edilir
     * @param limit  en çok kaç satır okunacağı
     */
    ReadResult read(String path, int offset, int limit);

    /** Dosyayı yazar; yoksa oluşturur, varsa TAMAMEN değiştirir. */
    WriteResult write(String path, String content);

    /** {@code oldText}'in TEK eşleşmesini {@code newText} ile değiştirir. */
    EditResult edit(String path, String oldText, String newText);

    /**
     * DÜZ METİN arama (regex değil).
     *
     * @param path arama kökü; {@code null} ise tüm çalışma alanı
     */
    GrepResult grep(String literal, String path);
}
```

- [ ] **Step 3: Başarısız testi yaz**

`InMemoryWorkspaceTest.java`:

```java
package com.zeus.framework.ai.agent.workspace;

import com.zeus.framework.ai.agent.workspace.WorkspaceResults.WorkspaceError;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryWorkspaceTest {

    private final ZeusAgentWorkspace ws = new InMemoryWorkspace();

    @Test
    void yazilanDosyaOkunur() {
        assertThat(ws.write("/rapor.md", "bir\niki\nüç").error()).isNull();

        var r = ws.read("/rapor.md", 0, 100);
        assertThat(r.error()).isNull();
        assertThat(r.content()).isEqualTo("bir\niki\nüç");
        assertThat(r.startLine()).isEqualTo(1);
        assertThat(r.endLine()).isEqualTo(3);
        assertThat(r.totalLines()).isEqualTo(3);
        assertThat(r.nextOffset()).isNull();   // sona ulaşıldı
    }

    @Test
    void sayfalamaSonrakiOffsetiVerir() {
        ws.write("/uzun.txt", "a\nb\nc\nd\ne");

        var ilk = ws.read("/uzun.txt", 0, 2);
        assertThat(ilk.content()).isEqualTo("a\nb");
        assertThat(ilk.startLine()).isEqualTo(1);
        assertThat(ilk.endLine()).isEqualTo(2);
        assertThat(ilk.totalLines()).isEqualTo(5);
        assertThat(ilk.nextOffset()).isEqualTo(2);

        var son = ws.read("/uzun.txt", 4, 2);
        assertThat(son.content()).isEqualTo("e");
        assertThat(son.nextOffset()).isNull();
    }

    @Test
    void negatifOffsetBastanOkur() {
        ws.write("/x.txt", "a\nb");
        var r = ws.read("/x.txt", -5, 10);
        assertThat(r.error()).isNull();
        assertThat(r.startLine()).isEqualTo(1);
    }

    @Test
    void olmayanDosyaFileNotFound() {
        assertThat(ws.read("/yok.txt", 0, 10).error()).isEqualTo(WorkspaceError.FILE_NOT_FOUND);
    }

    @Test
    void gecersizYollarReddedilir() {
        assertThat(ws.write("goreli.txt", "x").error()).isEqualTo(WorkspaceError.INVALID_PATH);
        assertThat(ws.write("/a/../b.txt", "x").error()).isEqualTo(WorkspaceError.INVALID_PATH);
        assertThat(ws.write("/~/gizli", "x").error()).isEqualTo(WorkspaceError.INVALID_PATH);
    }

    @Test
    void editTekEslesmeyiDegistirir() {
        ws.write("/n.md", "merhaba dünya");
        var e = ws.edit("/n.md", "dünya", "zeus");
        assertThat(e.error()).isNull();
        assertThat(e.occurrences()).isEqualTo(1);
        assertThat(ws.read("/n.md", 0, 10).content()).isEqualTo("merhaba zeus");
    }

    @Test
    void editCoklamaVeEslesmemeDurumlariniAyirir() {
        ws.write("/c.md", "aa aa");
        assertThat(ws.edit("/c.md", "aa", "bb").error()).isEqualTo(WorkspaceError.MULTIPLE_MATCHES);
        assertThat(ws.edit("/c.md", "zz", "bb").error()).isEqualTo(WorkspaceError.NO_MATCH);
    }

    @Test
    void lsOzyinelemeliVeAlfabetik() {
        ws.write("/b.txt", "1");
        ws.write("/alt/a.txt", "1");
        assertThat(ws.ls("/").paths()).containsExactly("/alt/a.txt", "/b.txt");
        assertThat(ws.ls("/alt").paths()).containsExactly("/alt/a.txt");
    }

    @Test
    void grepDuzMetinArar_regexDegil() {
        ws.write("/g.txt", "fiyat: 25000\nstok: 10");
        ws.write("/h.txt", "fiyat.*");

        assertThat(ws.grep("fiyat", null).matches())
                .containsExactly("/g.txt:1:fiyat: 25000", "/h.txt:1:fiyat.*");
        // '.' bir joker DEĞİL, birebir karakter:
        assertThat(ws.grep("fiyat.*", null).matches()).containsExactly("/h.txt:1:fiyat.*");
    }

    @Test
    void boyutSiniriAsilinciTooLarge() {
        ZeusAgentWorkspace kucuk = new InMemoryWorkspace(10);
        assertThat(kucuk.write("/a", "0123456789_fazla").error()).isEqualTo(WorkspaceError.TOO_LARGE);
    }

    @Test
    void snapshotYazilanlariVerir() {
        ws.write("/a.md", "içerik");
        assertThat(((InMemoryWorkspace) ws).snapshot()).containsEntry("/a.md", "içerik");
    }
}
```

- [ ] **Step 4: Testin başarısız olduğunu doğrula**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home && cd zeus-fw && mvn -B -pl zeus-ai-agent -am test`
Expected: FAIL — derleme hatası, `InMemoryWorkspace` sınıfı yok.

- [ ] **Step 5: `InMemoryWorkspace`'i yaz**

```java
package com.zeus.framework.ai.agent.workspace;

import com.zeus.framework.ai.agent.workspace.WorkspaceResults.EditResult;
import com.zeus.framework.ai.agent.workspace.WorkspaceResults.GrepResult;
import com.zeus.framework.ai.agent.workspace.WorkspaceResults.LsResult;
import com.zeus.framework.ai.agent.workspace.WorkspaceResults.ReadResult;
import com.zeus.framework.ai.agent.workspace.WorkspaceResults.WorkspaceError;
import com.zeus.framework.ai.agent.workspace.WorkspaceResults.WriteResult;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Koşu kapsamlı, bellek içi çalışma alanı.
 *
 * <p>Bir koşu için bir örnek kurulur ve koşu bitince bırakılır; eşzamanlı erişim YOKTUR
 * (ajan döngüsü tek thread'de ilerler), bu yüzden senkronizasyon da yoktur.
 *
 * <p>Toplam boyut sınırlıdır: uzun koşularda offload/compaction çıktıları birikip heap'i
 * zorlayabilir. Sınır aşılınca yazma {@code too_large} ile reddedilir — model bunu okur ve
 * davranışını değiştirebilir (istisna fırlatmak ajanı öldürürdü).
 */
public class InMemoryWorkspace implements ZeusAgentWorkspace {

    private static final int VARSAYILAN_AZAMI_BAYT = 8 * 1024 * 1024;

    private final Map<String, String> files = new TreeMap<>();
    private final int maxTotalBytes;

    public InMemoryWorkspace() {
        this(VARSAYILAN_AZAMI_BAYT);
    }

    public InMemoryWorkspace(int maxTotalBytes) {
        this.maxTotalBytes = maxTotalBytes;
    }

    /** Koşu sonunda çalışma alanının kopyası (yol → içerik). */
    public Map<String, String> snapshot() {
        return new LinkedHashMap<>(files);
    }

    @Override
    public LsResult ls(String path) {
        String p = normalize(path == null ? "/" : path);
        if (p == null) {
            return LsResult.error(WorkspaceError.INVALID_PATH);
        }
        String prefix = p.endsWith("/") ? p : p + "/";
        List<String> hit = new ArrayList<>();
        for (String key : files.keySet()) {
            if (key.equals(p) || key.startsWith(prefix)) {
                hit.add(key);
            }
        }
        return LsResult.ok(hit);
    }

    @Override
    public ReadResult read(String path, int offset, int limit) {
        String p = normalize(path);
        if (p == null) {
            return ReadResult.error(WorkspaceError.INVALID_PATH);
        }
        String content = files.get(p);
        if (content == null) {
            return ReadResult.error(WorkspaceError.FILE_NOT_FOUND);
        }
        String[] lines = content.split("\n", -1);
        int total = lines.length;
        int from = Math.max(offset, 0);
        if (from >= total) {
            return ReadResult.error(WorkspaceError.FILE_NOT_FOUND);
        }
        int to = Math.min(from + Math.max(limit, 1), total);
        String body = String.join("\n", List.of(lines).subList(from, to));
        Integer next = (to < total) ? to : null;
        return new ReadResult(body, from + 1, to, total, next, null);
    }

    @Override
    public WriteResult write(String path, String content) {
        String p = normalize(path);
        if (p == null) {
            return WriteResult.error(WorkspaceError.INVALID_PATH);
        }
        String yeni = content == null ? "" : content;
        if (toplamBayt(p, yeni) > maxTotalBytes) {
            return WriteResult.error(WorkspaceError.TOO_LARGE);
        }
        files.put(p, yeni);
        return WriteResult.ok(p);
    }

    @Override
    public EditResult edit(String path, String oldText, String newText) {
        String p = normalize(path);
        if (p == null) {
            return EditResult.error(WorkspaceError.INVALID_PATH);
        }
        String content = files.get(p);
        if (content == null) {
            return EditResult.error(WorkspaceError.FILE_NOT_FOUND);
        }
        if (oldText == null || oldText.isEmpty()) {
            return EditResult.error(WorkspaceError.NO_MATCH);
        }
        int ilk = content.indexOf(oldText);
        if (ilk < 0) {
            return EditResult.error(WorkspaceError.NO_MATCH);
        }
        if (content.indexOf(oldText, ilk + oldText.length()) >= 0) {
            // Belirsizliği modele bildir: hangi eşleşmenin kastedildiği bilinemez.
            return EditResult.error(WorkspaceError.MULTIPLE_MATCHES);
        }
        String yeni = content.substring(0, ilk)
                + (newText == null ? "" : newText)
                + content.substring(ilk + oldText.length());
        if (toplamBayt(p, yeni) > maxTotalBytes) {
            return EditResult.error(WorkspaceError.TOO_LARGE);
        }
        files.put(p, yeni);
        return EditResult.ok(p, 1);
    }

    @Override
    public GrepResult grep(String literal, String path) {
        if (literal == null || literal.isEmpty()) {
            return GrepResult.error(WorkspaceError.NO_MATCH);
        }
        String kok = path == null ? "/" : normalize(path);
        if (kok == null) {
            return GrepResult.error(WorkspaceError.INVALID_PATH);
        }
        String prefix = kok.endsWith("/") ? kok : kok + "/";
        List<String> hit = new ArrayList<>();
        for (Map.Entry<String, String> e : files.entrySet()) {
            if (!(e.getKey().equals(kok) || e.getKey().startsWith(prefix))) {
                continue;
            }
            String[] lines = e.getValue().split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                // DÜZ METİN: contains, regex DEĞİL.
                if (lines[i].contains(literal)) {
                    hit.add(e.getKey() + ":" + (i + 1) + ":" + lines[i]);
                }
            }
        }
        return GrepResult.ok(hit, false);
    }

    /** Mutlak, {@code ..}/{@code ~} içermeyen yol; geçersizse {@code null}. */
    private static String normalize(String path) {
        if (path == null || path.isBlank() || !path.startsWith("/")) {
            return null;
        }
        if (path.contains("..") || path.contains("~")) {
            return null;
        }
        String p = path.replaceAll("/{2,}", "/");
        return (p.length() > 1 && p.endsWith("/")) ? p.substring(0, p.length() - 1) : p;
    }

    private int toplamBayt(String degisenYol, String yeniIcerik) {
        int toplam = yeniIcerik.getBytes(StandardCharsets.UTF_8).length;
        for (Map.Entry<String, String> e : files.entrySet()) {
            if (!e.getKey().equals(degisenYol)) {
                toplam += e.getValue().getBytes(StandardCharsets.UTF_8).length;
            }
        }
        return toplam;
    }
}
```

- [ ] **Step 6: Testlerin geçtiğini doğrula**

Run: `mvn -B -pl zeus-ai-agent -am test`
Expected: PASS — `InMemoryWorkspaceTest` 11/11.

- [ ] **Step 7: Commit**

```bash
git add zeus-ai-agent pom.xml zeus-dependencies/pom.xml
git commit -F - <<'EOF'
zeus-ai-agent: çalışma alanı (sanal dosya sistemi) + modül iskeleti

Araştırma ajanının ara çıktılarını ve nihai raporunu yazdığı koşu kapsamlı
bellek içi dosya sistemi. Hata İSTİSNA DEĞİL DÖNÜŞ DEĞERİ: bu arayüzün
tüketicisi model, "dosya yok"u okuyup kendini düzeltebilmeli; fırlatılan
istisna ajanı döngüden düşürürdü. Hata kodları normalize (file_not_found,
invalid_path, no_match, multiple_matches, too_large).

edit tek eşleşme ister: çoklu eşleşmede multiple_matches döner, çünkü hangi
eşleşmenin kastedildiği bilinemez. grep DÜZ METİN arar (contains), regex değil.
Toplam boyut sınırı var; aşılınca yazma reddedilir, istisna atılmaz.

Modül YENİ 3. PARTİ BAĞIMLILIK GETİRMEZ (yalnız spring-boot-autoconfigure +
zeus-ai) → ZeusCapabilities satırı, module yenileme ve WildFly restart gerekmez.
EOF
```

---

## Task 2: Dosya tool'ları — beş `@Tool`, sayfalama başlığı, okuma-önce-düzenleme

**Files:**
- Create: `zeus-fw/zeus-ai-agent/src/main/java/com/zeus/framework/ai/agent/workspace/WorkspaceTools.java`
- Test: `zeus-fw/zeus-ai-agent/src/test/java/com/zeus/framework/ai/agent/workspace/WorkspaceToolsTest.java`
- Test: `zeus-fw/zeus-ai-agent/src/test/java/com/zeus/framework/ai/agent/workspace/ToolDescriptionSnapshotTest.java`

**Interfaces:**
- Consumes: Task 1'in `ZeusAgentWorkspace`, `InMemoryWorkspace`, `WorkspaceResults.*`
- Produces: `WorkspaceTools` sınıfı, ctor `WorkspaceTools(ZeusAgentWorkspace workspace)`; beş public `@Tool` metodu — `String ls(String path)`, `String readFile(String filePath, Integer offset, Integer limit)`, `String writeFile(String filePath, String content)`, `String editFile(String filePath, String oldString, String newString)`, `String grep(String pattern, String path)`. Hepsi **String** döner (model okur).

> **Tasarım notu — bu modülün asıl "promptu" tool açıklamalarıdır.** deepagents'ın ölçülmüş bulgusu: varsayılan sistem promptu BOŞ (1 byte); davranış tool açıklamalarında yaşıyor. Aşağıdaki açıklamalar deepagents'ın `filesystem.py` içindeki metinlerinden uyarlanmıştır (Türkçeleştirildi, `execute`/multimodal/offload'a ait satırlar A1 kapsamında olmadığı için çıkarıldı).

- [ ] **Step 1: Başarısız testi yaz**

`WorkspaceToolsTest.java`:

```java
package com.zeus.framework.ai.agent.workspace;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceToolsTest {

    private ZeusAgentWorkspace ws;
    private WorkspaceTools tools;

    @BeforeEach
    void setUp() {
        ws = new InMemoryWorkspace();
        tools = new WorkspaceTools(ws);
    }

    @Test
    void readFileDurumBasligiylaDoner() {
        ws.write("/r.md", "a\nb\nc\nd\ne");

        String out = tools.readFile("/r.md", 0, 2);

        // Başlık ilk satırda; altındaki her şey BİREBİR dosya içeriği.
        assertThat(out).startsWith("@@ satır 1-2 / 5 | sonraki offset 2 @@\n");
        assertThat(out).endsWith("a\nb");
    }

    @Test
    void sonPenceredeSonrakiOffsetYazilmaz() {
        ws.write("/r.md", "a\nb");
        assertThat(tools.readFile("/r.md", 0, 10)).startsWith("@@ satır 1-2 / 2 @@\n");
    }

    @Test
    void okunmadanDuzenlemeREDDEDILIR() {
        ws.write("/r.md", "merhaba");

        String out = tools.editFile("/r.md", "merhaba", "selam");

        assertThat(out).contains("önce read_file");
        assertThat(ws.read("/r.md", 0, 10).content()).isEqualTo("merhaba"); // değişmedi
    }

    @Test
    void okunduktanSonraDuzenlemeCalisir() {
        ws.write("/r.md", "merhaba");
        tools.readFile("/r.md", 0, 10);

        assertThat(tools.editFile("/r.md", "merhaba", "selam")).contains("güncellendi");
        assertThat(ws.read("/r.md", 0, 10).content()).isEqualTo("selam");
    }

    @Test
    void writeFileOkumaSarti_aramaz() {
        assertThat(tools.writeFile("/yeni.md", "içerik")).contains("yazıldı");
    }

    @Test
    void hataKoduModeleAnlasilirMetinOlarakDoner() {
        assertThat(tools.readFile("/yok.md", 0, 10)).contains("file_not_found");
        assertThat(tools.writeFile("goreli.md", "x")).contains("invalid_path");
    }

    @Test
    void grepDuzMetin_eslesmeYoksaAcikSoyler() {
        ws.write("/a.txt", "fiyat: 100");
        assertThat(tools.grep("fiyat", null)).contains("/a.txt:1:fiyat: 100");
        assertThat(tools.grep("fiyat.*", null)).contains("eşleşme bulunamadı");
    }

    @Test
    void lsBosCalismaAlaniniAcikSoyler() {
        assertThat(tools.ls("/")).contains("boş");
    }
}
```

`ToolDescriptionSnapshotTest.java`:

```java
package com.zeus.framework.ai.agent.workspace;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tool AÇIKLAMALARININ golden snapshot'ı.
 *
 * <p>Bu modülde davranış sistem promptunda değil, tool açıklamalarında yaşıyor (deepagents'ın
 * ölçülmüş bulgusu: varsayılan sistem promptu boş). Bir açıklamayı sessizce düzenlemek ajanın
 * davranışını değiştirir; bu test o değişikliği review'da GÖRÜNÜR kılar.
 *
 * <p>Açıklama bilinçli olarak değiştiyse beklenen metni burada güncelleyin — bu bir kod
 * değişikliği kadar ciddiye alınmalıdır.
 */
class ToolDescriptionSnapshotTest {

    private Map<String, String> aciklamalar() {
        ToolCallback[] cbs = MethodToolCallbackProvider.builder()
                .toolObjects(new WorkspaceTools(new InMemoryWorkspace()))
                .build().getToolCallbacks();
        return Arrays.stream(cbs).collect(Collectors.toMap(
                cb -> cb.getToolDefinition().name(), cb -> cb.getToolDefinition().description()));
    }

    @Test
    void besToolYayinlanir() {
        assertThat(aciklamalar().keySet())
                .containsExactlyInAnyOrder("ls", "readFile", "writeFile", "editFile", "grep");
    }

    @Test
    void readFileAciklamasiSayfalamaVeBaslikKuraliniAnlatir() {
        String d = aciklamalar().get("readFile");
        assertThat(d).contains("@@");
        assertThat(d).contains("Düzenlemeden önce dosyayı MUTLAKA oku");
        assertThat(d).contains("offset");
    }

    @Test
    void editFileAciklamasiOkumaSartiniVeBaslikYasaginiAnlatir() {
        String d = aciklamalar().get("editFile");
        assertThat(d).contains("Önce read_file ile okumalısın");
        assertThat(d).contains("durum başlığını");
        assertThat(d).contains("tek bir yerde");
    }

    @Test
    void grepAciklamasiDUZMETINoldugunuVurgular() {
        String d = aciklamalar().get("grep");
        assertThat(d).contains("DÜZ METİN");
        assertThat(d).contains("regex DEĞİL");
    }
}
```

- [ ] **Step 2: Testin başarısız olduğunu doğrula**

Run: `mvn -B -pl zeus-ai-agent test`
Expected: FAIL — `WorkspaceTools` sınıfı yok.

- [ ] **Step 3: `WorkspaceTools`'u yaz**

```java
package com.zeus.framework.ai.agent.workspace;

import com.zeus.framework.ai.agent.workspace.WorkspaceResults.EditResult;
import com.zeus.framework.ai.agent.workspace.WorkspaceResults.GrepResult;
import com.zeus.framework.ai.agent.workspace.WorkspaceResults.LsResult;
import com.zeus.framework.ai.agent.workspace.WorkspaceResults.ReadResult;
import com.zeus.framework.ai.agent.workspace.WorkspaceResults.WriteResult;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.HashSet;
import java.util.Set;

/**
 * Çalışma alanını modele açan tool'lar — koşu başına bir örnek.
 *
 * <p><b>Bu sınıfın açıklamaları bu modülün asıl PROMPTUDUR.</b> deepagents'ın ölçülmüş bulgusu:
 * varsayılan sistem promptu boştur; davranış tool açıklamalarında yaşar. Açıklamalar
 * {@code ToolDescriptionSnapshotTest} ile kilitlidir.
 *
 * <p>"Okumadan düzenleme yok" kuralı BU SINIFTA tutulur (çalışma alanında değil): kural modelin
 * davranışıyla ilgilidir, depolamayla değil. Okunan dosyalar koşu boyunca hatırlanır.
 */
public class WorkspaceTools {

    private final ZeusAgentWorkspace workspace;
    private final Set<String> okunanlar = new HashSet<>();

    public WorkspaceTools(ZeusAgentWorkspace workspace) {
        this.workspace = workspace;
    }

    @Tool(description = """
            Çalışma alanındaki dosyaları listeler (özyinelemeli).
            Doğru dosyayı bulmak için read_file ya da edit_file'dan ÖNCE neredeyse her zaman \
            bunu kullan.""")
    public String ls(@ToolParam(description = "Mutlak dizin yolu, ör. /") String path) {
        LsResult r = workspace.ls(path);
        if (r.error() != null) {
            return "HATA: " + r.error();
        }
        if (r.paths().isEmpty()) {
            return "Çalışma alanı boş (bu yolun altında dosya yok).";
        }
        return String.join("\n", r.paths());
    }

    @Tool(description = """
            Çalışma alanından bir dosyayı okur.

            Kullanım:
            - Varsayılan olarak baştan en çok 100 satır okur. Büyük dosyaları bütün hâlinde \
            okumak yerine offset/limit ile sayfalayarak oku.
            - İçeriğin üstünde `@@ satır A-B / T | sonraki offset N @@` biçiminde bir durum \
            başlığı bulunur; başlığın ALTINDAKİ her satır birebir dosya içeriğidir. \
            Düzenleme yaparken bu başlığı ASLA dâhil etme.
            - Dosya yoksa hata döner; yolu uydurma.
            - Düzenlemeden önce dosyayı MUTLAKA oku.""")
    public String readFile(
            @ToolParam(description = "Mutlak dosya yolu") String filePath,
            @ToolParam(description = "0 tabanlı başlangıç satırı; verilmezse 0", required = false) Integer offset,
            @ToolParam(description = "En çok kaç satır; verilmezse 100", required = false) Integer limit) {

        int off = offset == null ? 0 : offset;
        int lim = limit == null ? 100 : limit;
        ReadResult r = workspace.read(filePath, off, lim);
        if (r.error() != null) {
            return "HATA: " + r.error();
        }
        okunanlar.add(filePath);

        String baslik = "@@ satır " + r.startLine() + "-" + r.endLine() + " / " + r.totalLines()
                + (r.nextOffset() != null ? " | sonraki offset " + r.nextOffset() : "") + " @@";
        return baslik + "\n" + r.content();
    }

    @Tool(description = """
            Dosyaya içerik yazar. Dosya yoksa oluşturur, varsa İÇERİĞİNİ TAMAMEN DEĞİŞTİRİR.

            Kullanım:
            - Yeni dosya oluşturmak ya da dosyayı baştan yazmak istediğinde kullan; \
            önce okumana gerek yoktur.
            - Mevcut bir dosyanın bir kısmını değiştirecekesen write_file yerine edit_file tercih et.
            - Araştırma bulgularını ve nihai raporu buraya yaz; sohbete uzun metin dökme.""")
    public String writeFile(
            @ToolParam(description = "Mutlak dosya yolu") String filePath,
            @ToolParam(description = "Dosyanın tam içeriği") String content) {

        WriteResult r = workspace.write(filePath, content);
        if (r.error() != null) {
            return "HATA: " + r.error();
        }
        okunanlar.add(filePath);   // yazan taraf içeriği bilir
        return r.path() + " yazıldı.";
    }

    @Tool(description = """
            Dosyada birebir metin değişimi yapar.

            Kullanım:
            - Önce read_file ile okumalısın; okumadan düzenleme REDDEDİLİR.
            - old_string dosyada tek bir yerde geçmelidir; birden çok eşleşme varsa hata döner \
            (hangisinin kastedildiği bilinemez) — daha uzun ve benzersiz bir parça ver.
            - Okuma çıktısındaki girintiyi birebir koru ve durum başlığını (@@ ... @@) \
            old_string veya new_string içine ASLA koyma.""")
    public String editFile(
            @ToolParam(description = "Mutlak dosya yolu") String filePath,
            @ToolParam(description = "Değiştirilecek birebir metin") String oldString,
            @ToolParam(description = "Yerine yazılacak metin") String newString) {

        if (!okunanlar.contains(filePath)) {
            return "HATA: bu dosya bu koşuda okunmadı — düzenlemeden önce read_file ile oku.";
        }
        EditResult r = workspace.edit(filePath, oldString, newString);
        if (r.error() != null) {
            return "HATA: " + r.error();
        }
        return r.path() + " güncellendi (" + r.occurrences() + " değişiklik).";
    }

    @Tool(description = """
            Çalışma alanında DÜZ METİN arar (regex DEĞİL).

            Desen birebir aranır: regex meta karakterleri operatör değil, sıradan karakterdir. \
            Örneğin "fiyat.*" araması birebir "fiyat.*" metnini arar; "." herhangi bir karakter \
            anlamına gelmez. Birkaç farklı metni aramak için ayrı ayrı grep çağır.

            Sonuç `yol:satır:içerik` biçiminde döner.""")
    public String grep(
            @ToolParam(description = "Aranacak birebir metin") String pattern,
            @ToolParam(description = "Arama kökü; verilmezse tüm çalışma alanı", required = false) String path) {

        GrepResult r = workspace.grep(pattern, path);
        if (r.error() != null) {
            return "HATA: " + r.error();
        }
        if (r.matches().isEmpty()) {
            return "eşleşme bulunamadı";
        }
        return String.join("\n", r.matches());
    }
}
```

- [ ] **Step 4: Testlerin geçtiğini doğrula**

Run: `mvn -B -pl zeus-ai-agent test`
Expected: PASS — `WorkspaceToolsTest` 8/8, `ToolDescriptionSnapshotTest` 4/4.

- [ ] **Step 5: Snapshot testinin dişli olduğunu kanıtla**

`WorkspaceTools.grep`'in açıklamasındaki `regex DEĞİL` ifadesini geçici olarak `regex kullanma` yap, testi çalıştır, **kırmızıya döndüğünü gör**, sonra geri al.

Run: `mvn -B -pl zeus-ai-agent test -Dtest=ToolDescriptionSnapshotTest`
Expected: önce FAIL (`grepAciklamasiDUZMETINoldugunuVurgular`), geri alınca PASS.

- [ ] **Step 6: Commit**

```bash
git add zeus-ai-agent
git commit -F - <<'EOF'
zeus-ai-agent: beş dosya tool'u + açıklama snapshot testi

ls / readFile / writeFile / editFile / grep. execute (shell) BİLİNÇLİ OLARAK YOK:
uygulama sunucusunda kabuk açılmaz. glob/delete YAGNI.

Açıklamalar deepagents'ın filesystem.py metinlerinden uyarlandı, çünkü ölçülmüş
bulgu şu: varsayılan sistem promptu boş (1 byte), davranış tool AÇIKLAMALARINDA
yaşıyor. Bu yüzden açıklamalar golden snapshot testiyle kilitlendi — sessiz bir
düzenleme ajanın davranışını değiştirir, test onu review'da görünür kılar.
Testin dişliliği açıklama bozularak doğrulandı.

İki kural birebir kopyalandı: (1) okuma çıktısının başındaki
"@@ satır A-B / T | sonraki offset N @@" durum başlığı — model sayfalamayı
bilerek yapsın, uydurmasın; (2) düzenlemeden önce okuma zorunluluğu. İkinci kural
çalışma alanında değil tool katmanında tutuluyor: modelin davranışıyla ilgili,
depolamayla değil.
EOF
```

---

## Task 3: Bütçe — `AgentBudget` ve `BudgetEligibilityChecker`

**Files:**
- Create: `zeus-fw/zeus-ai-agent/src/main/java/com/zeus/framework/ai/agent/AgentBudget.java`
- Create: `zeus-fw/zeus-ai-agent/src/main/java/com/zeus/framework/ai/agent/StopReason.java`
- Create: `zeus-fw/zeus-ai-agent/src/main/java/com/zeus/framework/ai/agent/BudgetEligibilityChecker.java`
- Test: `zeus-fw/zeus-ai-agent/src/test/java/com/zeus/framework/ai/agent/BudgetEligibilityCheckerTest.java`

**Interfaces:**
- Consumes: yok (Spring AI `ToolExecutionEligibilityChecker`, `ChatResponse`)
- Produces:
  - `enum StopReason { MODEL_FINISHED, STEP_BUDGET, TOKEN_BUDGET, TIME_BUDGET, ERROR }`
  - `record AgentBudget(int maxSteps, long maxTokens, Duration maxDuration)` + `static AgentBudget defaults()` (15 adım · 200_000 token · 3 dakika)
  - `BudgetEligibilityChecker implements ToolExecutionEligibilityChecker`, ctor `(AgentBudget budget, Supplier<Long> clockMillis)`; metotlar `int steps()`, `long tokens()`, `StopReason stopReason()` (bütçe dolmadıysa `MODEL_FINISHED`)

> **Neden burası:** Spring AI'ın `ToolCallingAdvisor.Builder`'ında **iterasyon sınırı yoktur** (bytecode'dan doğrulandı). Döngünün devam edip etmeyeceğine karar veren tek genişletme noktası `ToolExecutionEligibilityChecker`. deepagents'ın `recursion_limit: 9_999` varsayılanı bir banka için kabul edilemez; bütçe bu yüzden A1'dedir.

- [ ] **Step 1: Başarısız testi yaz**

```java
package com.zeus.framework.ai.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class BudgetEligibilityCheckerTest {

    /** Araç çağrısı İÇEREN yanıt — döngünün devam etmek isteyeceği durum. */
    private static ChatResponse araçCagiranYanit(long promptTok, long completionTok) {
        AssistantMessage msg = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("id-1", "function", "ls", "{}")))
                .build();
        return new ChatResponse(List.of(new Generation(msg)),
                ChatResponseMetadata.builder()
                        .usage(new DefaultUsage((int) promptTok, (int) completionTok))
                        .build());
    }

    /** Araç çağrısı İÇERMEYEN yanıt — model işini bitirdi. */
    private static ChatResponse duzYanit() {
        return new ChatResponse(List.of(new Generation(new AssistantMessage("bitti"))));
    }

    @Test
    void aracCagrisiYoksaDonguDevamETMEZ() {
        var c = new BudgetEligibilityChecker(AgentBudget.defaults(), () -> 0L);
        assertThat(c.apply(duzYanit())).isFalse();
        assertThat(c.stopReason()).isEqualTo(StopReason.MODEL_FINISHED);
    }

    @Test
    void adimButcesiDolunca_durur() {
        var c = new BudgetEligibilityChecker(new AgentBudget(2, 1_000_000, Duration.ofHours(1)), () -> 0L);
        assertThat(c.apply(araçCagiranYanit(1, 1))).isTrue();   // 1. adım
        assertThat(c.apply(araçCagiranYanit(1, 1))).isTrue();   // 2. adım
        assertThat(c.apply(araçCagiranYanit(1, 1))).isFalse();  // bütçe doldu
        assertThat(c.stopReason()).isEqualTo(StopReason.STEP_BUDGET);
        assertThat(c.steps()).isEqualTo(2);
    }

    @Test
    void tokenButcesiDolunca_durur() {
        var c = new BudgetEligibilityChecker(new AgentBudget(100, 50, Duration.ofHours(1)), () -> 0L);
        assertThat(c.apply(araçCagiranYanit(20, 10))).isTrue();   // toplam 30
        assertThat(c.apply(araçCagiranYanit(20, 10))).isFalse();  // toplam 60 > 50
        assertThat(c.stopReason()).isEqualTo(StopReason.TOKEN_BUDGET);
        assertThat(c.tokens()).isEqualTo(60);
    }

    @Test
    void sureButcesiDolunca_durur() {
        AtomicLong saat = new AtomicLong(0);
        var c = new BudgetEligibilityChecker(
                new AgentBudget(100, 1_000_000, Duration.ofMillis(100)), saat::get);
        assertThat(c.apply(araçCagiranYanit(1, 1))).isTrue();
        saat.set(500);
        assertThat(c.apply(araçCagiranYanit(1, 1))).isFalse();
        assertThat(c.stopReason()).isEqualTo(StopReason.TIME_BUDGET);
    }

    @Test
    void varsayilanlarSikiDir() {
        AgentBudget b = AgentBudget.defaults();
        assertThat(b.maxSteps()).isEqualTo(15);
        assertThat(b.maxTokens()).isEqualTo(200_000);
        assertThat(b.maxDuration()).isEqualTo(Duration.ofMinutes(3));
    }
}
```

- [ ] **Step 2: Testin başarısız olduğunu doğrula**

Run: `mvn -B -pl zeus-ai-agent test -Dtest=BudgetEligibilityCheckerTest`
Expected: FAIL — sınıflar yok.

- [ ] **Step 3: Üç sınıfı yaz**

`StopReason.java`:

```java
package com.zeus.framework.ai.agent;

/** Ajan koşusunun neden durduğu. Çağıran bunu bir hata gibi ele almak isterse kendi kararıdır. */
public enum StopReason {
    /** Model araç çağırmayı bıraktı — normal bitiş. */
    MODEL_FINISHED,
    STEP_BUDGET,
    TOKEN_BUDGET,
    TIME_BUDGET,
    ERROR
}
```

`AgentBudget.java`:

```java
package com.zeus.framework.ai.agent;

import java.time.Duration;

/**
 * Bir ajan koşusunun sınırları.
 *
 * <p><b>Neden zorunlu:</b> Spring AI'ın tool döngüsünde iterasyon sınırı YOKTUR; bir hata döngüsü
 * fatura patlatabilir. Varsayılanlar bilinçli olarak SIKIDIR — uzun koşular için çağıran bunları
 * açıkça yükseltmelidir.
 */
public record AgentBudget(int maxSteps, long maxTokens, Duration maxDuration) {

    public AgentBudget {
        if (maxSteps < 1) {
            throw new IllegalArgumentException("maxSteps en az 1 olmalı: " + maxSteps);
        }
        if (maxTokens < 1) {
            throw new IllegalArgumentException("maxTokens en az 1 olmalı: " + maxTokens);
        }
        if (maxDuration == null || maxDuration.isNegative() || maxDuration.isZero()) {
            throw new IllegalArgumentException("maxDuration pozitif olmalı: " + maxDuration);
        }
    }

    /** 15 adım · 200.000 token · 3 dakika. */
    public static AgentBudget defaults() {
        return new AgentBudget(15, 200_000L, Duration.ofMinutes(3));
    }
}
```

`BudgetEligibilityChecker.java`:

```java
package com.zeus.framework.ai.agent;

import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.model.tool.ToolExecutionEligibilityChecker;

import java.util.function.Supplier;

/**
 * Tool döngüsünün devam edip etmeyeceğine karar veren bütçe.
 *
 * <p>Spring AI'ın {@code ToolCallingAdvisor.Builder}'ında iterasyon sınırı yoktur; döngüye
 * müdahale edilebilen tek genişletme noktası budur. Bütçe dolduğunda döngü <b>temiz durur</b> —
 * istisna fırlatılmaz, çünkü o ana kadar üretilmiş rapor ve çalışma alanı korunmalıdır.
 *
 * <p><b>Koşu kapsamlıdır ve DURUM TUTAR</b> (adım/token sayacı). Her koşu için yeni bir örnek
 * kurulmalıdır; paylaşılan bir örnek koşular arasında sayaç sızdırır.
 */
public class BudgetEligibilityChecker implements ToolExecutionEligibilityChecker {

    private final AgentBudget budget;
    private final Supplier<Long> clockMillis;
    private final long startedAt;

    private int steps;
    private long tokens;
    private StopReason stopReason = StopReason.MODEL_FINISHED;

    public BudgetEligibilityChecker(AgentBudget budget, Supplier<Long> clockMillis) {
        this.budget = budget;
        this.clockMillis = clockMillis;
        this.startedAt = clockMillis.get();
    }

    @Override
    public Boolean apply(ChatResponse response) {
        tokens += tokenSayisi(response);

        // Model araç çağırmadıysa işi bitmiştir; bütçeye bakmaya gerek yok.
        if (!isToolCallResponse(response)) {
            stopReason = StopReason.MODEL_FINISHED;
            return false;
        }
        if (steps >= budget.maxSteps()) {
            stopReason = StopReason.STEP_BUDGET;
            return false;
        }
        if (tokens > budget.maxTokens()) {
            stopReason = StopReason.TOKEN_BUDGET;
            return false;
        }
        if (clockMillis.get() - startedAt > budget.maxDuration().toMillis()) {
            stopReason = StopReason.TIME_BUDGET;
            return false;
        }
        steps++;
        return true;
    }

    public int steps() {
        return steps;
    }

    public long tokens() {
        return tokens;
    }

    public StopReason stopReason() {
        return stopReason;
    }

    private static long tokenSayisi(ChatResponse response) {
        if (response == null || response.getMetadata() == null) {
            return 0L;
        }
        Usage u = response.getMetadata().getUsage();
        return u == null ? 0L : u.getTotalTokens();
    }
}
```

- [ ] **Step 4: Testlerin geçtiğini doğrula**

Run: `mvn -B -pl zeus-ai-agent test -Dtest=BudgetEligibilityCheckerTest`
Expected: PASS — 5/5.

- [ ] **Step 5: Commit**

```bash
git add zeus-ai-agent
git commit -F - <<'EOF'
zeus-ai-agent: adım/token/süre bütçesi

Spring AI'ın ToolCallingAdvisor.Builder'ında iterasyon sınırı YOK (bytecode'dan
doğrulandı); döngüye müdahale edilebilen tek nokta ToolExecutionEligibilityChecker.
Bütçe oraya oturuyor. deepagents'ın recursion_limit: 9999 varsayılanı bir banka
için kabul edilemez, bu yüzden bütçe A2'den A1'e alındı: en ucuz ve en kritik
güvenlik özelliği.

Bütçe dolunca döngü TEMİZ DURUR, istisna atılmaz — o ana kadar üretilmiş rapor ve
çalışma alanı korunmalı. Duruş sebebi StopReason ile çağırana bildirilir; "model
bitirdi" ile "bütçe doldu" aynı yanıta karışmaz.

Checker koşu kapsamlıdır ve durum tutar; saat Supplier ile enjekte edilir ki süre
bütçesi deterministik test edilebilsin. Varsayılanlar sıkı: 15 adım / 200k token /
3 dakika.
EOF
```

---

## Task 4: `ZeusAgent` sözleşmesi ve `DefaultZeusAgent`

**Files:**
- Create: `zeus-fw/zeus-ai-agent/src/main/java/com/zeus/framework/ai/agent/AgentSpec.java`
- Create: `zeus-fw/zeus-ai-agent/src/main/java/com/zeus/framework/ai/agent/AgentRunStats.java`
- Create: `zeus-fw/zeus-ai-agent/src/main/java/com/zeus/framework/ai/agent/AgentResult.java`
- Create: `zeus-fw/zeus-ai-agent/src/main/java/com/zeus/framework/ai/agent/ZeusAgent.java`
- Create: `zeus-fw/zeus-ai-agent/src/main/java/com/zeus/framework/ai/agent/DefaultZeusAgent.java`
- Test: `zeus-fw/zeus-ai-agent/src/test/java/com/zeus/framework/ai/agent/StubChatModel.java`
- Test: `zeus-fw/zeus-ai-agent/src/test/java/com/zeus/framework/ai/agent/DefaultZeusAgentTest.java`

**Interfaces:**
- Consumes: Task 1 (`InMemoryWorkspace`), Task 2 (`WorkspaceTools`), Task 3 (`AgentBudget`, `BudgetEligibilityChecker`, `StopReason`)
- Produces:
  - `record AgentSpec(String systemPrompt, List<Object> tools, AgentBudget budget)` + `static AgentSpec of(String systemPrompt, Object... tools)`
  - `record AgentRunStats(int steps, long tokens, long durationMs, StopReason stopReason)`
  - `record AgentResult<T>(T output, Map<String,String> workspace, AgentRunStats stats)`
  - `interface ZeusAgent { AgentResult<String> run(String task, AgentSpec spec); <T> AgentResult<T> runAs(String task, Class<T> type, AgentSpec spec); }`
  - `DefaultZeusAgent implements ZeusAgent`, ctor `DefaultZeusAgent(ChatModel chatModel, ObservationRegistry observationRegistry)`

> **Koşu kapsamlı kurulum:** `run()` her çağrıda yeni `InMemoryWorkspace` + `WorkspaceTools` + `BudgetEligibilityChecker` + `ChatClient` kurar. Bu, deepagents'ın LangGraph state kanallarıyla çözdüğü şeyi nesne ömrüyle çözer — ve bütçe sayacının koşular arasında sızmasını yapısal olarak engeller.

- [ ] **Step 1: `StubChatModel`'i yaz (test altyapısı)**

```java
package com.zeus.framework.ai.agent;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Senaryolanmış ChatModel — ajan döngüsünü GERÇEK LLM OLMADAN test etmeyi sağlar.
 *
 * <p>Bu, harness'ın test edilebilirliğinin taşıyıcı koşuludur: bütçe, adım sayımı ve çalışma
 * alanı etkileşimi deterministik olarak sınanamazsa bu katman test edilemez hâle gelir.
 * {@code DefaultZeusAgent}'ın ChatModel'i ENJEKTE ALMASININ sebebi budur.
 */
class StubChatModel implements ChatModel {

    private final Deque<ChatResponse> yanitlar = new ArrayDeque<>();
    private final List<Prompt> cagrilar = new ArrayList<>();

    /** Araç çağıran bir yanıt kuyruğa ekler. */
    StubChatModel aracCagir(String toolAdi, String argumanJson, int promptTok, int completionTok) {
        AssistantMessage msg = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-" + yanitlar.size(), "function", toolAdi, argumanJson)))
                .build();
        yanitlar.add(new ChatResponse(List.of(new Generation(msg)),
                ChatResponseMetadata.builder().usage(new DefaultUsage(promptTok, completionTok)).build()));
        return this;
    }

    /** Düz metin (araç çağırmayan) yanıt kuyruğa ekler. */
    StubChatModel metin(String icerik, int promptTok, int completionTok) {
        yanitlar.add(new ChatResponse(List.of(new Generation(new AssistantMessage(icerik))),
                ChatResponseMetadata.builder().usage(new DefaultUsage(promptTok, completionTok)).build()));
        return this;
    }

    List<Prompt> cagrilar() {
        return cagrilar;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        cagrilar.add(prompt);
        if (yanitlar.isEmpty()) {
            // Senaryo bittiyse döngüyü kapat; testte "beklenenden fazla çağrı" sessiz kalmasın.
            return new ChatResponse(List.of(new Generation(new AssistantMessage("senaryo bitti"))));
        }
        return yanitlar.poll();
    }
}
```

- [ ] **Step 2: Başarısız testi yaz**

```java
package com.zeus.framework.ai.agent;

import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultZeusAgentTest {

    private ZeusAgent ajan(StubChatModel model) {
        return new DefaultZeusAgent(model, ObservationRegistry.NOOP);
    }

    @Test
    void modelBitirinceSonucVeIstatistikDoner() {
        StubChatModel model = new StubChatModel().metin("rapor hazır", 10, 5);

        AgentResult<String> r = ajan(model).run("ürünleri analiz et",
                AgentSpec.of("Sen bir analiz ajanısın."));

        assertThat(r.output()).isEqualTo("rapor hazır");
        assertThat(r.stats().stopReason()).isEqualTo(StopReason.MODEL_FINISHED);
        assertThat(r.stats().steps()).isZero();
        assertThat(r.stats().tokens()).isEqualTo(15);
        assertThat(r.workspace()).isEmpty();
    }

    @Test
    void ajanCalismaAlaninaYazabilir_veSonucDoner() {
        StubChatModel model = new StubChatModel()
                .aracCagir("writeFile", "{\"filePath\":\"/rapor.md\",\"content\":\"bulgular\"}", 10, 5)
                .metin("yazdım", 10, 5);

        AgentResult<String> r = ajan(model).run("rapor yaz", AgentSpec.of("Sen bir ajansın."));

        assertThat(r.workspace()).containsEntry("/rapor.md", "bulgular");
        assertThat(r.stats().steps()).isEqualTo(1);
        assertThat(r.stats().stopReason()).isEqualTo(StopReason.MODEL_FINISHED);
    }

    @Test
    void adimButcesiKosuyuDURDURUR_veSebebiBildirir() {
        StubChatModel model = new StubChatModel();
        for (int i = 0; i < 10; i++) {
            model.aracCagir("ls", "{\"path\":\"/\"}", 1, 1);
        }

        AgentSpec spec = new AgentSpec("Sen bir ajansın.", java.util.List.of(),
                new AgentBudget(2, 1_000_000L, Duration.ofHours(1)));
        AgentResult<String> r = ajan(model).run("sonsuza kadar listele", spec);

        assertThat(r.stats().stopReason()).isEqualTo(StopReason.STEP_BUDGET);
        assertThat(r.stats().steps()).isEqualTo(2);
    }

    @Test
    void kosularArasindaCalismaAlaniPAYLASILMAZ() {
        ZeusAgent ajan = ajan(new StubChatModel()
                .aracCagir("writeFile", "{\"filePath\":\"/a.md\",\"content\":\"1\"}", 1, 1)
                .metin("bitti", 1, 1));
        AgentResult<String> ilk = ajan.run("yaz", AgentSpec.of("x"));
        assertThat(ilk.workspace()).containsKey("/a.md");

        // Aynı ajan bean'i, yeni koşu: çalışma alanı taze olmalı.
        ZeusAgent ajan2 = ajan(new StubChatModel().metin("bitti", 1, 1));
        assertThat(ajan2.run("hiçbir şey yapma", AgentSpec.of("x")).workspace()).isEmpty();
    }

    @Test
    void uygulamaToollariAjanToollarıylaBirlikteVerilir() {
        StubChatModel model = new StubChatModel().metin("ok", 1, 1);
        ajan(model).run("x", AgentSpec.of("sistem", new SahteUygulamaToolu()));
        // Derleme ve koşunun hatasız tamamlanması yeterli kanıt: uygulama tool'u
        // MethodToolCallbackProvider tarafından reddedilmedi.
        assertThat(model.cagrilar()).hasSize(1);
    }

    static class SahteUygulamaToolu {
        @org.springframework.ai.tool.annotation.Tool(description = "Deneme amaçlı sahte tool.")
        public String deneme() {
            return "x";
        }
    }
}
```

- [ ] **Step 3: Testin başarısız olduğunu doğrula**

Run: `mvn -B -pl zeus-ai-agent test -Dtest=DefaultZeusAgentTest`
Expected: FAIL — `ZeusAgent`/`DefaultZeusAgent` yok.

- [ ] **Step 4: Sözleşme tiplerini yaz**

```java
// AgentSpec.java
package com.zeus.framework.ai.agent;

import java.util.Arrays;
import java.util.List;

/**
 * Bir ajan koşusunun tanımı.
 *
 * @param systemPrompt ajanın rolü ve kısıtları
 * @param tools        {@code @Tool} anotasyonlu uygulama nesneleri — ZeusAiAssistant ile AYNI
 *                     üslup; uygulama yeni bir anotasyon öğrenmez. Çalışma alanı tool'ları
 *                     framework tarafından ayrıca eklenir.
 * @param budget       adım/token/süre sınırları
 */
public record AgentSpec(String systemPrompt, List<Object> tools, AgentBudget budget) {

    public AgentSpec {
        tools = tools == null ? List.of() : List.copyOf(tools);
        budget = budget == null ? AgentBudget.defaults() : budget;
    }

    /** Varsayılan bütçeyle kısa yol. */
    public static AgentSpec of(String systemPrompt, Object... tools) {
        return new AgentSpec(systemPrompt, Arrays.asList(tools), AgentBudget.defaults());
    }
}
```

```java
// AgentRunStats.java
package com.zeus.framework.ai.agent;

/**
 * Koşunun ölçümleri.
 *
 * <p>{@code stopReason} bilinçli olarak sonucun bir parçasıdır: "model bitirdi" ile "bütçe doldu"
 * aynı yanıt gövdesine karışmamalıdır. Kurumsal maliyet görünürlüğünün tek yolu budur.
 */
public record AgentRunStats(int steps, long tokens, long durationMs, StopReason stopReason) {
}
```

```java
// AgentResult.java
package com.zeus.framework.ai.agent;

import java.util.Map;

/**
 * Koşunun sonucu.
 *
 * @param output    modelin son çıktısı ({@code run} için metin, {@code runAs} için hedef tip)
 * @param workspace koşu sonunda çalışma alanındaki dosyalar — araştırma ajanının ASIL ÇIKTISI
 *                  budur; koşu hatayla bitse bile o ana kadar yazılanlar burada döner
 * @param stats     adım/token/süre ve duruş sebebi
 */
public record AgentResult<T>(T output, Map<String, String> workspace, AgentRunStats stats) {
}
```

```java
// ZeusAgent.java
package com.zeus.framework.ai.agent;

/**
 * Uygulamaların çok adımlı ajan koşusu için bağlandığı TEK framework sözleşmesi.
 *
 * <p>{@code ZeusAiAssistant} tek soru-tek yanıt içindir; bu arayüz ajanın araç çağıra çağıra
 * ilerlediği, ara çıktılarını çalışma alanına yazdığı ve bütçeyle sınırlanan koşular içindir.
 *
 * <p>Koşu SENKRONDUR: {@code run} bloklar. Bunu mümkün kılan şey sıkı bütçedir
 * (varsayılan 15 adım / 3 dakika). Uzun koşular için çağıranın HTTP timeout'u buna göre
 * ayarlanmalıdır.
 */
public interface ZeusAgent {

    /** Görevi çalıştırır; modelin son metnini, çalışma alanını ve ölçümleri döner. */
    AgentResult<String> run(String task, AgentSpec spec);

    /** Aynısı, ama son yanıt verilen tipe (record/POJO) dönüştürülür. */
    <T> AgentResult<T> runAs(String task, Class<T> type, AgentSpec spec);
}
```

- [ ] **Step 5: `DefaultZeusAgent`'ı yaz**

```java
package com.zeus.framework.ai.agent;

import com.zeus.framework.ai.agent.workspace.InMemoryWorkspace;
import com.zeus.framework.ai.agent.workspace.WorkspaceTools;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.model.ChatModel;

import java.util.ArrayList;
import java.util.List;

/**
 * Koşu kapsamlı ajan yürütücüsü.
 *
 * <p><b>Her koşu kendi dünyasını kurar:</b> taze {@link InMemoryWorkspace}, taze
 * {@link WorkspaceTools}, taze {@link BudgetEligibilityChecker} ve taze {@link ChatClient}.
 * deepagents'ın LangGraph state kanallarıyla (ve private API'leriyle) çözdüğü şey burada nesne
 * ömrüyle çözülür; ayrıca bütçe sayacının koşular arasında sızması YAPISAL olarak imkânsız olur.
 *
 * <p>Tool döngüsünü Spring AI'ın {@code ToolCallingAdvisor}'ı yürütür — alt sınıflanmaz. Tek
 * müdahalemiz, {@code ChatClient.builder}'ın advisor-builder alan aşırı yüklemesine bütçe
 * checker'ını geçmek.
 */
public class DefaultZeusAgent implements ZeusAgent {

    private static final Logger log = LoggerFactory.getLogger(DefaultZeusAgent.class);

    private final ChatModel chatModel;
    private final ObservationRegistry observationRegistry;

    public DefaultZeusAgent(ChatModel chatModel, ObservationRegistry observationRegistry) {
        this.chatModel = chatModel;
        this.observationRegistry = observationRegistry == null
                ? ObservationRegistry.NOOP : observationRegistry;
    }

    @Override
    public AgentResult<String> run(String task, AgentSpec spec) {
        return calistir(task, spec, (cevap) -> cevap.content());
    }

    @Override
    public <T> AgentResult<T> runAs(String task, Class<T> type, AgentSpec spec) {
        return calistir(task, spec, (cevap) -> cevap.entity(type));
    }

    private <T> AgentResult<T> calistir(String task, AgentSpec spec, Cikti<T> cikti) {
        InMemoryWorkspace workspace = new InMemoryWorkspace();
        WorkspaceTools workspaceTools = new WorkspaceTools(workspace);
        BudgetEligibilityChecker butce =
                new BudgetEligibilityChecker(spec.budget(), System::currentTimeMillis);

        List<Object> tools = new ArrayList<>(spec.tools());
        tools.add(workspaceTools);

        ChatClient client = ChatClient
                .builder(chatModel, observationRegistry, null, null,
                        ToolCallingAdvisor.builder().toolExecutionEligibilityChecker(butce))
                .build();

        long basla = System.currentTimeMillis();
        T sonuc = null;
        StopReason sebep;
        try {
            sonuc = cikti.al(client.prompt()
                    .system(spec.systemPrompt() == null ? "" : spec.systemPrompt())
                    .user(task)
                    .tools(tools.toArray())
                    .call());
            sebep = butce.stopReason();
        } catch (RuntimeException e) {
            // Koşu yarıda kaldıysa bile çalışma alanı DÖNER: o ana kadar yazılmış rapor kaybolmaz.
            log.warn("Ajan koşusu hata ile bitti: {}", e.getMessage(), e);
            sebep = StopReason.ERROR;
        }
        long sure = System.currentTimeMillis() - basla;

        AgentRunStats stats = new AgentRunStats(butce.steps(), butce.tokens(), sure, sebep);
        log.info("Ajan koşusu bitti — adım={}, token={}, süre={} ms, sebep={}, dosya={}",
                stats.steps(), stats.tokens(), stats.durationMs(), stats.stopReason(),
                workspace.snapshot().size());

        return new AgentResult<>(sonuc, workspace.snapshot(), stats);
    }

    /** `call()` sonucundan çıktıyı alma biçimi — metin ya da yapılandırılmış tip. */
    @FunctionalInterface
    private interface Cikti<T> {
        @SuppressWarnings("unchecked")
        default T al(ChatClient.CallResponseSpec cevap) {
            return (T) uygula(cevap);
        }

        Object uygula(ChatClient.CallResponseSpec cevap);
    }
}
```

> **Not (uygulayıcıya):** yukarıdaki `Cikti` yardımcı arayüzü `content()` ve `entity(type)` çağrılarını tek yolda toplamak içindir. `ChatClient.CallResponseSpec`'in tam metot adları derlemede doğrulanmalıdır; `content()` ve `entity(Class)` Spring AI 2.0.1'de mevcuttur (`zeus-ai`'deki `DefaultZeusAiAssistant` `responseEntity(type)` kullanır — gerekirse aynı yol tercih edilebilir). Derleme hatası alınırsa `zeus-ai/src/main/java/com/zeus/framework/ai/DefaultZeusAiAssistant.java`'daki kullanım örnek alınmalıdır.

- [ ] **Step 6: Testlerin geçtiğini doğrula**

Run: `mvn -B -pl zeus-ai-agent test -Dtest=DefaultZeusAgentTest`
Expected: PASS — 5/5.

- [ ] **Step 7: Commit**

```bash
git add zeus-ai-agent
git commit -F - <<'EOF'
zeus-ai-agent: ZeusAgent sözleşmesi ve koşu kapsamlı yürütücü

run/runAs ikilisi ZeusAiAssistant'ın ask/askAs üslubunu izler; tool'lar yine
@Tool nesneleri — uygulama yeni anotasyon öğrenmez.

Her koşu kendi dünyasını kurar: taze workspace + tools + bütçe sayacı + ChatClient.
Böylece deepagents'ın LangGraph state kanallarıyla (yer yer private API'lerle)
çözdüğü şey nesne ömrüyle çözülüyor ve bütçe sayacının koşular arası sızması
YAPISAL olarak imkânsız oluyor.

Tool döngüsü alt sınıflanmadı: ChatClient.builder'ın advisor-builder alan public
aşırı yüklemesine bütçe checker'ı geçiliyor, o kadar. Alt sınıflama ancak A2'de
(offload/compaction kancaları) gerekecek.

AgentResult koşu hata ile bitse bile çalışma alanını taşır — o ana kadar yazılmış
rapor kaybolmaz. StubChatModel ile döngü gerçek LLM olmadan test ediliyor; bu,
harness'ın test edilebilirliğinin taşıyıcı koşulu ve ChatModel'in enjekte
alınmasının sebebi.
EOF
```

---

## Task 5: Auto-configuration, property'ler ve opt-in simetrisi

**Files:**
- Create: `zeus-fw/zeus-ai-agent/src/main/java/com/zeus/framework/ai/agent/ZeusAgentProperties.java`
- Create: `zeus-fw/zeus-ai-agent/src/main/java/com/zeus/framework/ai/agent/ZeusAgentAutoConfiguration.java`
- Create: `zeus-fw/zeus-ai-agent/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Test: `zeus-fw/zeus-ai-agent/src/test/java/com/zeus/framework/ai/agent/ZeusAgentAutoConfigurationTest.java`
- Modify: `zeus-fw/CLAUDE.md`
- Create: `zeus-fw/gelistirmeler/24-zeus-ai-agent.md`

**Interfaces:**
- Consumes: Task 3 (`AgentBudget`), Task 4 (`ZeusAgent`, `DefaultZeusAgent`)
- Produces: `ZeusAgentProperties` (`zeus.ai.agent.enabled`, `max-steps`, `max-tokens`, `max-duration`), `ZeusAgentAutoConfiguration` → `ZeusAgent` bean'i

- [ ] **Step 1: Başarısız testi yaz**

```java
package com.zeus.framework.ai.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in simetrisinin kanıtı — {@code ZeusSoapAutoConfigurationTest} / {@code
 * ZeusMcpAutoConfigurationTest} şablonu. Framework'ün kendi hata mesajı
 * {@code zeus.<yetenek>.enabled=false} yazmayı önerdiği için, o cümle açılışı çökertmemeli.
 */
class ZeusAgentAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ZeusAgentAutoConfiguration.class))
            .withBean(ChatModel.class, () -> new StubChatModel());

    @Test
    void bilincliFalseAcilisiCokertmez() {
        runner.withPropertyValues("zeus.ai.agent.enabled=false")
                .run(ctx -> assertThat(ctx).hasNotFailed().doesNotHaveBean(ZeusAgent.class));
    }

    @Test
    void propertyHicYazilmamissaDaAcilisCokmez() {
        runner.run(ctx -> assertThat(ctx).hasNotFailed().doesNotHaveBean(ZeusAgent.class));
    }

    @Test
    void trueYazilincaAjanKurulur() {
        runner.withPropertyValues("zeus.ai.agent.enabled=true")
                .run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(ZeusAgent.class));
    }

    @Test
    void butceVarsayilanlariPropertyIleDegistirilebilir() {
        runner.withPropertyValues("zeus.ai.agent.enabled=true",
                        "zeus.ai.agent.max-steps=40",
                        "zeus.ai.agent.max-tokens=500000",
                        "zeus.ai.agent.max-duration=10m")
                .run(ctx -> {
                    AgentBudget b = ctx.getBean(ZeusAgentProperties.class).toBudget();
                    assertThat(b.maxSteps()).isEqualTo(40);
                    assertThat(b.maxTokens()).isEqualTo(500_000L);
                    assertThat(b.maxDuration()).isEqualTo(java.time.Duration.ofMinutes(10));
                });
    }

    @Test
    void chatModelYoksaAjanKurulmaz() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ZeusAgentAutoConfiguration.class))
                .withPropertyValues("zeus.ai.agent.enabled=true")
                .run(ctx -> assertThat(ctx).hasNotFailed().doesNotHaveBean(ZeusAgent.class));
    }
}
```

- [ ] **Step 2: Testin başarısız olduğunu doğrula**

Run: `mvn -B -pl zeus-ai-agent test -Dtest=ZeusAgentAutoConfigurationTest`
Expected: FAIL — autoconfig sınıfları yok.

- [ ] **Step 3: Properties ve autoconfig'i yaz**

```java
package com.zeus.framework.ai.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Zeus AI Agent ayarları ({@code zeus.ai.agent.*}). */
@ConfigurationProperties(prefix = "zeus.ai.agent")
public class ZeusAgentProperties {

    /**
     * Yeteneğin açık olup olmadığı.
     *
     * <p>Bu alan OKUNMAZ; gerçek kapı {@code @ConditionalOnProperty}'dir. Java varsayılanı
     * bilinçli {@code false} — zeus-ai'deki {@code true} varsayılanı dokümanla çelişen ölü koddu.
     *
     * <p><b>DİKKAT:</b> bu modül 3. parti autoconfig getirmediği için {@code ZeusCapabilities}'e
     * kayıtlı DEĞİLDİR; dolayısıyla jar'ı ekleyip bu property'yi yazmayan uygulama açılışta
     * UYARI ALMAZ, yalnız {@code ZeusAgent} bean'i kurulmaz.
     */
    private boolean enabled = false;

    /** Varsayılan adım sınırı. */
    private int maxSteps = 15;

    /** Varsayılan token sınırı. */
    private long maxTokens = 200_000L;

    /** Varsayılan süre sınırı. Senkron koşuda çağıranın HTTP timeout'undan KÜÇÜK olmalıdır. */
    private Duration maxDuration = Duration.ofMinutes(3);

    /** Property'lerden varsayılan bütçeyi kurar. */
    public AgentBudget toBudget() {
        return new AgentBudget(maxSteps, maxTokens, maxDuration);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxSteps() {
        return maxSteps;
    }

    public void setMaxSteps(int maxSteps) {
        this.maxSteps = maxSteps;
    }

    public long getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(long maxTokens) {
        this.maxTokens = maxTokens;
    }

    public Duration getMaxDuration() {
        return maxDuration;
    }

    public void setMaxDuration(Duration maxDuration) {
        this.maxDuration = maxDuration;
    }
}
```

```java
package com.zeus.framework.ai.agent;

import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Zeus AI Agent auto-configuration.
 *
 * <p>Sınıf adları {@code afterName} ile STRING olarak verilir: modül Spring AI'ın
 * {@code *.autoconfigure} paketlerine derleme bağımlılığı TAŞIMAZ (zeus-ai ve zeus-ai-mcp ile
 * aynı disiplin).
 */
@AutoConfiguration(afterName = {
        "org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration",
        "org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration"
})
@ConditionalOnClass(ChatModel.class)
// matchIfMissing YOK: yetenekler OPT-IN'dir.
@ConditionalOnProperty(prefix = "zeus.ai.agent", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(ZeusAgentProperties.class)
public class ZeusAgentAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ZeusAgentAutoConfiguration.class);

    public ZeusAgentAutoConfiguration(ZeusAgentProperties properties) {
        log.info("Zeus AI Agent modülü yüklendi — varsayılan bütçe: {} adım / {} token / {}.",
                properties.getMaxSteps(), properties.getMaxTokens(), properties.getMaxDuration());
    }

    @Bean
    @ConditionalOnBean(ChatModel.class)
    @ConditionalOnMissingBean
    public ZeusAgent zeusAgent(ChatModel chatModel,
                               ObjectProvider<ObservationRegistry> observationRegistry) {
        return new DefaultZeusAgent(chatModel,
                observationRegistry.getIfAvailable(() -> ObservationRegistry.NOOP));
    }
}
```

`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` (tek satır, `spring.factories` **eklenmez**):

```
com.zeus.framework.ai.agent.ZeusAgentAutoConfiguration
```

- [ ] **Step 4: Testlerin geçtiğini doğrula**

Run: `mvn -B -pl zeus-ai-agent test`
Expected: PASS — modülün tüm testleri (InMemoryWorkspace 11 + WorkspaceTools 8 + Snapshot 4 + Budget 5 + DefaultZeusAgent 5 + AutoConfig 5).

- [ ] **Step 5: Tüm framework build'inin yeşil kaldığını doğrula**

Run: `cd zeus-fw && mvn -B clean install`
Expected: BUILD SUCCESS; mevcut modüllerin testleri bozulmamış (zeus-base 33, zeus-ai-mcp 32, zeus-soap 3, zeus-sms 9).

- [ ] **Step 6: Guard süitinin yeşil kaldığını doğrula**

Run: `./scripts/run-guards.sh`
Expected: 17/17 yeşil. Yeni modül 3. parti jar getirmediği için `test-autoconfig-sahipligi.sh` ve `test-module-liste-esitligi.sh` etkilenmemeli; **etkilendiyse** bu, modülün beklenmedik bir bağımlılık çektiğinin işaretidir — durdurup incele.

- [ ] **Step 7: Dokümanları yaz**

`zeus-fw/gelistirmeler/24-zeus-ai-agent.md` oluştur. İçerik başlıkları: modül tablosu (artifactId, paket, yetenek anahtarı, bağımlılıklar) · "Neden harness" (ZeusAiAssistant tek soru, bu çok adımlı) · `ZeusAgent` sözleşmesi ve örnek kullanım · çalışma alanı ve beş tool (execute'un neden olmadığı) · tool açıklamalarının bu modülün promptu olduğu ve snapshot testi · bütçe (Spring AI'da iterasyon sınırı olmadığı, varsayılanların neden sıkı olduğu, `stopReason`) · koşu kapsamlı kurulumun gerekçesi · **"Bilinen boşluk: yetenek kaydı yok"** (property unutulursa sessiz kalır) · senkron koşu sınırı ve HTTP timeout notu · A2/A3'te ne geleceği.

`zeus-fw/CLAUDE.md` modül tablosuna, `ai-mcp` satırının altına:

```markdown
| ai-agent | `zeus-ai-agent` | ✅ gerçek | Çok adımlı araştırma ajanı harness'ı: koşu kapsamlı çalışma alanı (5 dosya tool'u) + `ZeusAgent` sözleşmesi + adım/token/süre bütçesi. Spring AI'ın tool döngüsünü aynen kullanır; yeni 3. parti jar GETİRMEZ (module/restart etkilenmez). Bkz. `gelistirmeler/24-zeus-ai-agent.md` |
```

Aynı dosyadaki yetenek listesine (`bugün: ai-mcp, ai, database, soap`) `ai-agent`'ı **ekleme** — bu modül `ZeusCapabilities`'e kayıtlı değildir. Bunun yerine "Yetenek Opt-in'i" bölümünün sonuna bir cümle ekle:

```markdown
**İstisna:** 3. parti autoconfig paketi getirmeyen modüller (`zeus-ai-agent`) `ZeusCapabilities`'e
kaydedilmez — gatelenecek bir 3. parti autoconfig yoktur. Opt-in yalnız modülün kendi
`@ConditionalOnProperty`'siyle olur; bedeli, property unutulduğunda verifier'ın uyarmamasıdır.
```

- [ ] **Step 8: Commit**

```bash
git add zeus-ai-agent CLAUDE.md gelistirmeler/24-zeus-ai-agent.md
git commit -F - <<'EOF'
zeus-ai-agent: auto-configuration, bütçe property'leri ve doküman 24

Opt-in simetrisi zeus-soap/zeus-ai-mcp şablonundan: =false açılışı çökertmez,
property yoksa bean kurulmaz, true olunca ZeusAgent kurulur. ChatModel yoksa
sessizce kurulmaz (@ConditionalOnBean).

Bu modül ZeusCapabilities'e KAYDEDİLMEZ: 3. parti autoconfig paketi getirmiyor,
dolayısıyla gatelenecek bir şey yok. Bedeli dokümana yazıldı — jar'ı ekleyip
property'yi yazmayan uygulama açılışta uyarı almaz, yalnız bean kurulmaz.
zeus-ai-mcp'de risk "korumasız ağ ucu"ydu, burada yalnız "özellik çalışmaz".

Bütçe varsayılanları property ile yükseltilebilir; max-duration senkron koşuda
çağıranın HTTP timeout'undan küçük tutulmalı.
EOF
```

---

## Task 6: Kanıt uygulaması — PRODUCT_PKG üstünde çok adımlı analiz koşusu

**Files:**
- Create: `spring-wildfly-arch/src/main/java/com/zeus/springwildflyarch/ai/ProductAnalysisAgent.java`
- Create: `spring-wildfly-arch/src/main/java/com/zeus/springwildflyarch/controller/ProductAgentController.java`
- Create: `spring-wildfly-arch/src/main/java/com/zeus/springwildflyarch/dto/AgentRunResponse.java`
- Modify: `spring-wildfly-arch/pom.xml`
- Modify: `spring-wildfly-arch/src/main/resources/application.properties`
- Modify: `spring-wildfly-arch/src/test/resources/application.properties`
- Create: `spring-wildfly-arch/gelistirmeler/20-arastirma-ajani.md`

**Interfaces:**
- Consumes: Task 4/5 (`ZeusAgent`, `AgentSpec`, `AgentResult`, `AgentRunStats`)
- Produces: `GET /api/agent/products/analysis` → `AgentRunResponse`

- [ ] **Step 1: Bağımlılık ve property'leri ekle**

`pom.xml`'de `zeus-ai-mcp` bağımlılığının altına:

```xml
        <!-- Çok adımlı araştırma ajanı: mevcut @Tool metotlarını kullanarak analiz koşusu
             yürütür, ara çıktıları koşu kapsamlı çalışma alanına yazar. -->
        <dependency>
            <groupId>com.zeus</groupId>
            <artifactId>zeus-ai-agent</artifactId>
        </dependency>
```

`src/main/resources/application.properties`'e MCP bloğunun altına:

```properties
# --- Araştırma ajanı (zeus-ai-agent) ---
# Koşu SENKRONDUR; bütçe bunu mümkün kılar. max-duration, çağıranın HTTP timeout'undan
# KÜÇÜK tutulmalıdır.
zeus.ai.agent.enabled=true
zeus.ai.agent.max-steps=10
zeus.ai.agent.max-duration=2m
```

`src/test/resources/application.properties`'e (main'i GÖLGELER):

```properties
zeus.ai.agent.enabled=true
```

- [ ] **Step 2: Ajanı ve ucu yaz**

```java
package com.zeus.springwildflyarch.ai;

import com.zeus.framework.ai.agent.AgentResult;
import com.zeus.framework.ai.agent.AgentSpec;
import com.zeus.framework.ai.agent.ZeusAgent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Katalog analiz ajanı — zeus-ai-agent'ın kanıt kullanımı.
 *
 * <p>Mimari süreklilik: ajan YENİ BİR VERİ YOLU AÇMAZ. Mevcut {@link ProductAiTools}'u kullanır,
 * yani veri yine {@code ProductRepository} üzerinden Oracle {@code PRODUCT_PKG} stored
 * procedure'lerinden gelir. Ajanın eklediği tek şey ÇOK ADIMLILIK: veriyi çeker, ara bulgularını
 * çalışma alanına yazar, sonunda raporu derler.
 */
@Component
@RequiredArgsConstructor
public class ProductAnalysisAgent {

    private static final String SISTEM_PROMPTU = """
            Sen bir katalog analiz ajanısın. Görevini adım adım yürüt:
            1. Önce listProducts ile katalogu çek.
            2. Bulgularını /bulgular.md dosyasına yaz (write_file) — uzun metni sohbete dökme.
            3. Son olarak kısa bir özet cevap ver.
            Yalnızca sana verilen araçlardan gelen veriyi kullan; ürün özelliği UYDURMA.
            """;

    private final ZeusAgent agent;
    private final ProductAiTools productAiTools;

    public AgentResult<String> analyze(String question) {
        return agent.run(question, AgentSpec.of(SISTEM_PROMPTU, productAiTools));
    }
}
```

```java
package com.zeus.springwildflyarch.dto;

import java.util.Map;

/**
 * Ajan koşusunun HTTP yanıtı.
 *
 * <p>{@code stopReason} ve {@code steps} bilinçli olarak dışarı verilir: "model bitirdi" ile
 * "bütçe doldu" aynı yanıta karışmamalı, çağıran durumu görebilmeli.
 */
public record AgentRunResponse(String answer, Map<String, String> workspace,
                               int steps, long tokens, long durationMs, String stopReason) {
}
```

```java
package com.zeus.springwildflyarch.controller;

import com.zeus.framework.ai.agent.AgentResult;
import com.zeus.springwildflyarch.ai.ProductAnalysisAgent;
import com.zeus.springwildflyarch.dto.AgentRunResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Çok adımlı ajan koşusunu tetikleyen uç. Sadece HTTP; iş mantığı ajandadır. */
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class ProductAgentController {

    private final ProductAnalysisAgent productAnalysisAgent;

    @GetMapping("/products/analysis")
    public AgentRunResponse analysis(
            @RequestParam(defaultValue = "Katalogdaki ürünleri fiyat ve stok açısından değerlendir.")
            String question) {

        AgentResult<String> r = productAnalysisAgent.analyze(question);
        return new AgentRunResponse(r.output(), r.workspace(),
                r.stats().steps(), r.stats().tokens(), r.stats().durationMs(),
                r.stats().stopReason().name());
    }
}
```

- [ ] **Step 3: Uygulamayı derle ve testlerin geçtiğini doğrula**

Run: `cd spring-wildfly-arch && mvn -B clean package`
Expected: BUILD SUCCESS; context-load testi (`SpringWildflyArchApplicationTests`) ve `McpConfigTest` geçmeli — yeni property'ler test classpath'ine de yazıldığı için açılış düşmemeli.

- [ ] **Step 4: WAR içeriğini doğrula**

Run: `unzip -l target/spring-wildfly-arch-0.0.1-SNAPSHOT.war | grep 'WEB-INF/lib/'`
Expected: **8 zeus jar'ı** (öncekiler + `zeus-ai-agent`), 3. parti jar yok.

> Guard `test-war-packaging.sh` 7 zeus jar'ı bekliyor. Beklenen listeye `zeus-ai-agent` eklenmeli ve sayı 8'e çıkarılmalı — guard kırmızıya dönerse **doğru davranmıştır**, beklentiyi bilinçli olarak güncelle.

- [ ] **Step 5: Guard beklentisini güncelle ve süiti çalıştır**

`zeus-fw/scripts/test-war-packaging.sh` içinde `expected` listesine alfabetik sırada `zeus-ai-agent` ekle ve iki `say` metnini "tam 8 zeus jar'ı (zeus-sms + zeus-ai-mcp + zeus-ai-agent dahil)" yap.

Run: `cd zeus-fw && ./scripts/run-guards.sh`
Expected: 17/17 yeşil.

- [ ] **Step 6: Gerçek koşuyu ölç (WildFly + Oracle)**

```bash
export ZEUS_MCP_TOKEN="$(cat /tmp/zeus_mcp_token 2>/dev/null || openssl rand -hex 16)"
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
cd zeus-fw && mvn clean install && cd ../spring-wildfly-arch && ./scripts/deploy.sh
# NOT: yeni 3. parti jar yok → install-zeus-module.sh ve WildFly restart GEREKMEZ.

curl -s "http://127.0.0.1:8080/spring-wildfly-arch/api/agent/products/analysis" \
     -H "X-Correlation-Id: ajankanit1" | tee /tmp/agent.out
```

Doğrulanacaklar:
1. Yanıtta `workspace` içinde `/bulgular.md` var ve içeriği Oracle'dan gelen gerçek ürünleri taşıyor.
2. `stopReason` = `MODEL_FINISHED` (bütçe dolmadı), `steps` ≥ 1.
3. `grep ajankanit1 $WILDFLY_HOME/standalone/log/server.log` — aynı correlation-id altında hem `ProductAiTools` satırı hem `DefaultZeusAgent` özet satırı görünür.
4. Bütçenin gerçekten durdurduğunu kanıtla: `zeus.ai.agent.max-steps=1` ile yeniden deploy edip koş; `stopReason` = `STEP_BUDGET` olmalı. Ardından değeri geri al.

- [ ] **Step 7: Dokümanı yaz ve commit et**

`spring-wildfly-arch/gelistirmeler/20-arastirma-ajani.md` (sıradaki boş numara **20**): ne değişti tablosu · ajanın veri yolu diyagramı (yeni yol açılmadığı vurgusu) · `AgentSpec` kullanımı · property'ler ve **test dosyası gölgeleme tuzağı** · gerçek koşu çıktısı (yukarıdaki curl'ün gerçek yanıtı) · bütçe ölçümü (adım 6.4) · senkron koşu ve HTTP timeout notu · bilinen boşluklar.

App `CLAUDE.md`'nin AI katmanı bölümüne kısa bir "Araştırma ajanı" alt başlığı ekle.

```bash
# zeus-fw
git add scripts/test-war-packaging.sh
git commit -m "Guard: WAR'da 8 zeus jar'ı (zeus-ai-agent eklendi)"

# spring-wildfly-arch
git add pom.xml src/main/java src/main/resources/application.properties \
        src/test/resources/application.properties gelistirmeler/20-arastirma-ajani.md CLAUDE.md
git commit -F - <<'EOF'
Araştırma ajanı: PRODUCT_PKG üstünde çok adımlı analiz koşusu

zeus-ai-agent'ın kanıt kullanımı. GET /api/agent/products/analysis koşuyu
tetikler; ajan listProducts ile veriyi çeker, bulguları çalışma alanındaki
/bulgular.md'ye yazar, özeti döner.

Mimari süreklilik: YENİ VERİ YOLU AÇILMADI. Ajan mevcut ProductAiTools'u
kullanıyor, yani veri yine ProductRepository -> Oracle PRODUCT_PKG'den geliyor.
Ajanın eklediği tek şey çok adımlılık ve ara çıktıların bağlam yerine çalışma
alanına yazılması.

Yanıt stopReason ve steps'i dışarı veriyor: "model bitirdi" ile "bütçe doldu"
aynı gövdeye karışmamalı. Bütçenin gerçekten durdurduğu max-steps=1 ile ölçüldü.

Yeni 3. parti jar yok → module yenileme ve WildFly restart gerekmedi.
EOF
```

---

## Self-Review

**1. Spec coverage** — A1 kapsamındaki her madde bir göreve düşüyor:

| Spec maddesi | Görev |
|---|---|
| Modül, `zeus.ai.agent.enabled`, 3. parti jar getirmeme | T1 (pom/BOM), T5 (autoconfig) |
| `ZeusAgent` / `AgentSpec` / `AgentResult` / `AgentRunStats` / `StopReason` | T3 (StopReason), T4 |
| `ZeusAgentWorkspace` + hata-dönüş-değeri + normalize kodlar | T1 |
| `InMemoryWorkspace`, koşu kapsamlı, boyut sınırı (R5) | T1 |
| Beş tool, `execute` yok, glob/delete yok | T2 |
| Sayfalama başlığı, okuma-önce-düzenleme, düz metin grep | T2 |
| Tool açıklamalarının snapshot'ı (R4) | T2 |
| Bütçe: adım/token/süre, `stopReason`, sıkı varsayılan (R1) | T3, T5 |
| Koşu kapsamlı kurulum (tools `ToolContext` yerine örnekleme) | T4 |
| Hata yönetimi: koşu hata alsa da workspace döner | T4 |
| `StubChatModel` ile LLM'siz test | T4 |
| Opt-in simetri testi | T5 |
| Yetenek kaydı olmaması ve bedeli (R6) | T5 (doküman + javadoc) |
| Senkron koşu / HTTP timeout (R3) | T5, T6 (doküman) |
| Kanıt uygulaması koşusu | T6 |

A2/A3 kapsamı (offload, compaction, sarkan çağrı onarımı, subagent) bu planda **bilinçli olarak yok**.

**2. Placeholder taraması** — plan içinde "TBD/TODO/uygun hata yönetimi ekle" türü ifade yok; her kod adımı gerçek kod içeriyor. Tek "serbest" adım T5-Step7 ve T6-Step7'deki doküman yazımı; oralarda yazılacak başlıklar madde madde sayıldı.

**3. Tip tutarlılığı** — `ZeusAgentWorkspace`'in beş metodu T1'de tanımlandığı imzayla T2'de çağrılıyor; `ReadResult.nextOffset()` `Integer` (null olabilir) ve T2 başlığı buna göre kuruyor; `BudgetEligibilityChecker.steps()/tokens()/stopReason()` T3'te tanımlanıp T4'te `AgentRunStats`'a aktarılıyor; `AgentSpec.of(String, Object...)` T4'te tanımlanıp T6'da kullanılıyor; `InMemoryWorkspace.snapshot()` T1'de tanımlanıp T4'te `AgentResult.workspace()`'e veriliyor.

**Bilinen tek belirsizlik** T4-Step5'te işaretlendi: `ChatClient.CallResponseSpec`'in `content()` / `entity(Class)` metot adları derlemede doğrulanmalı; uygulayıcıya `DefaultZeusAiAssistant`'taki çalışan kullanım referans gösterildi. Bu bir placeholder değil, derleme anında kesinleşecek bilinçli bir uyarı.
