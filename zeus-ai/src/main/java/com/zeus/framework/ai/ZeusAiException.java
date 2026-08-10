package com.zeus.framework.ai;

/**
 * Model çağrısı başarısız olduğunda fırlatılır (endpoint erişilemedi, kimlik doğrulama
 * reddedildi, yanıt yapılandırılmış tipe dönüştürülemedi, ...).
 *
 * <p>{@link ZeusAiExceptionHandler} bunu 502 Bad Gateway + ProblemDetail'e çevirir:
 * hata sağlayıcıdan gelir, uygulamanın kendi hatası değildir.
 */
public class ZeusAiException extends RuntimeException {

    public ZeusAiException(String message, Throwable cause) {
        super(message, cause);
    }

    public ZeusAiException(String message) {
        super(message);
    }
}
