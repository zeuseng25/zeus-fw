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
