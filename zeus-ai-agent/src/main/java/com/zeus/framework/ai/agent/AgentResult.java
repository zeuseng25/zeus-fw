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
