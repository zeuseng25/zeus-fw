package com.zeus.framework.bff;

import org.springframework.web.servlet.function.HandlerFilterFunction;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * BFF filter uzantı noktası (Gateway Server MVC).
 *
 * <p>Uygulamalar cross-cutting davranışları (header zenginleştirme, korelasyon id, oturum
 * kontrolü, ...) bu arayüzü implemente eden bean'lerle tanımlar ve route tanımlarında
 * {@code .filter(zeusBffFilterBean)} ile zincire ekler. Arayüz, Spring'in
 * {@link HandlerFilterFunction}'ının BFF'e adlandırılmış halidir — ayrı bir yaşam döngüsü
 * eklemez; amaç 1000 uygulamada aynı tip imzasının kullanılmasıdır.
 *
 * <p>Login/oturum filter'ları için bkz. {@code zeus-bff-login} modülü (iskelet).
 */
public interface ZeusBffFilter extends HandlerFilterFunction<ServerResponse, ServerResponse> {
}
