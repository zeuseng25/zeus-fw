package com.zeus.framework.base;

/**
 * Kayıt bulunamadığında fırlatılır; {@link GlobalExceptionHandler} bunu 404'e çevirir.
 *
 * <p>Framework geneli ortak istisna — uygulamalar kendi "not found" durumları için bunu kullanır.
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }

    public ResourceNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
