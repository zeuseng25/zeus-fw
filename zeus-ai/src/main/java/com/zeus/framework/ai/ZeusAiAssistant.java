package com.zeus.framework.ai;

/**
 * Uygulamaların LLM ile konuşurken kullandığı TEK framework sözleşmesi.
 *
 * <p>Amaç: uygulama kodunun {@code ChatClient} akıcı API'sine, sağlayıcı sınıflarına ve
 * hata tiplerine doğrudan bağlanmaması. Sağlayıcı ya da Spring AI sürümü değiştiğinde
 * değişen yer framework olur, N uygulama değil.
 *
 * <p>Üç yetenek:
 * <ol>
 *   <li>{@link #ask} — düz metin sohbet.</li>
 *   <li>{@link #askAs} — yapılandırılmış çıktı: yanıt doğrudan bir record/POJO'ya bağlanır.</li>
 *   <li>Her iki metodun {@code tools} parametresi — {@code @Tool} anotasyonlu nesneler verilir,
 *       model gerektiğinde bunları çağırır. Döngüyü Spring AI 2.0'ın {@code ToolCallingAdvisor}'ı
 *       yürütür; uygulama tarafında ekstra kod yoktur.</li>
 * </ol>
 */
public interface ZeusAiAssistant {

    /**
     * Modele soru sorar, düz metin yanıt döner.
     *
     * @param userText kullanıcı mesajı
     * @param tools    modele açılacak {@code @Tool} anotasyonlu nesneler (opsiyonel)
     */
    String ask(String userText, Object... tools);

    /**
     * Modele soru sorar, yanıtı verilen tipe (record/POJO) dönüştürerek döner.
     * JSON şeması tipten türetilir ve prompt'a eklenir.
     *
     * @param userText kullanıcı mesajı
     * @param type     hedef tip
     * @param tools    modele açılacak {@code @Tool} anotasyonlu nesneler (opsiyonel)
     */
    <T> T askAs(String userText, Class<T> type, Object... tools);
}
