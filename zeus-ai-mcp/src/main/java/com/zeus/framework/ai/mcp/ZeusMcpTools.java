package com.zeus.framework.ai.mcp;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP'ye yayınlanacak tool nesnelerinin AÇIK kaydı — bir nesnenin harici ajanlara ulaşmasının
 * TEK yolu.
 *
 * <p>Uygulama bunu tek bir {@code @Bean} olarak verir:
 * <pre>{@code
 * @Bean
 * ZeusMcpTools zeusMcpTools(ProductAiTools productAiTools) {
 *     return ZeusMcpTools.of(productAiTools);
 * }
 * }</pre>
 *
 * <p><b>Neden tarama değil açık kayıt.</b> {@code @Tool}'u {@code ZeusAiAssistant.ask(...)}'a
 * vermek SÜREÇ İÇİ bir karardır ve çağrı başına verilir. MCP ise aynı metodu AĞ ÜZERİNDEN dış
 * ajanlara açar. "Classpath'teki tüm {@code @Tool} bean'lerini yayınla" kuralında, yarın biri
 * yazma yapan bir {@code @Tool} eklediğinde o da kimse bir şey yazmadan dışarı çıkar. Bu yüzden
 * yayın yüzeyi tek dosyada, açıkça durur ve code review'da tek satırda görülür.
 *
 * <p><b>Neden ham {@code List<Object>} değil kendi tipi.</b> Adlandırılmış bir tip
 * {@code @ConditionalOnBean(ZeusMcpTools.class)}'ı mümkün kılar (yetenek açık ama kayıt yoksa
 * sıfır tool ile açılmak için) ve "bu uygulama dışarı ne açıyor?" sorusunu grep'lenebilir yapar.
 *
 * <p>Doğrulama {@link #of} içinde, BEAN KURULUŞ ANINDA yapılır: {@code @Tool} metodu olmayan bir
 * nesne ya da iki nesnede aynı tool adı, ilk isteği beklemeden açılışta patlar. Bu kontroller
 * Spring AI'ın {@code MethodToolCallbackProvider}'ından bedavaya gelir.
 */
public final class ZeusMcpTools {

    private final List<Object> toolObjects;

    private ZeusMcpTools(List<Object> toolObjects) {
        this.toolObjects = List.copyOf(toolObjects);
    }

    /**
     * Verilen nesnelerin {@code @Tool} anotasyonlu metotlarını MCP'ye yayınlar.
     *
     * @param toolObjects {@code @Tool} metotları taşıyan nesneler (genelde uygulamanın kendi
     *                    {@code @Component}'leri); en az biri verilmelidir
     * @throws IllegalArgumentException nesne listesi boşsa, {@code null} öge varsa, aynı nesne
     *                                  iki kez verildiyse; ayrıca bir nesnede {@code @Tool}
     *                                  metodu yoksa ya da iki nesne aynı tool adını taşıyorsa
     *                                  (bu ikisi Spring AI'ın {@code MethodToolCallbackProvider}
     *                                  doğrulamasından gelir ve kendi mesajı yönlendirici olduğu
     *                                  için sarılmaz)
     */
    public static ZeusMcpTools of(Object... toolObjects) {
        if (toolObjects == null || toolObjects.length == 0) {
            throw new IllegalArgumentException(
                    "ZeusMcpTools.of(...) en az bir tool nesnesi gerektirir. "
                    + "Hiçbir şey yayınlamak istemiyorsanız ZeusMcpTools.none() kullanın.");
        }
        List<Object> kabul = new ArrayList<>(toolObjects.length);
        Map<Object, Boolean> gorulen = new IdentityHashMap<>();
        for (Object o : toolObjects) {
            if (o == null) {
                throw new IllegalArgumentException("ZeusMcpTools.of(...) null tool nesnesi kabul etmez.");
            }
            if (gorulen.put(o, Boolean.TRUE) != null) {
                throw new IllegalArgumentException(
                        "ZeusMcpTools.of(...) aynı nesneyi iki kez aldı: " + o.getClass().getName());
            }
            kabul.add(o);
        }
        ZeusMcpTools tools = new ZeusMcpTools(kabul);
        // Fail-fast: @Tool metodu yok / çift tool adı varsa BURADA patlar, ilk istekte değil.
        tools.toolCallbacks();
        return tools;
    }

    /**
     * Hiçbir şey yayınlamayan kayıt — kademeli açılış ve testler için.
     *
     * <p>Yetenek açıkken tool yayınlamamak meşru bir durumdur; bunu bean'i hiç tanımlamamak
     * yerine açıkça söylemek, "unutuldu mu, bilinçli mi?" sorusunu ortadan kaldırır.
     */
    public static ZeusMcpTools none() {
        return new ZeusMcpTools(List.of());
    }

    /** Yayınlanan nesneler (değiştirilemez). */
    public List<Object> toolObjects() {
        return toolObjects;
    }

    /**
     * Nesnelerin {@code @Tool} metotlarından türetilmiş callback'ler.
     *
     * <p>Boş kayıtta boş liste döner — {@code MethodToolCallbackProvider} hiç nesne verilmediğinde
     * hata fırlattığı için o yol ayrıca ele alınır.
     */
    List<ToolCallback> toolCallbacks() {
        if (toolObjects.isEmpty()) {
            return List.of();
        }
        return Arrays.asList(MethodToolCallbackProvider.builder()
                .toolObjects(toolObjects.toArray())
                .build()
                .getToolCallbacks());
    }
}
