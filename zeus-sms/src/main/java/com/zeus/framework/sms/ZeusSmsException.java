package com.zeus.framework.sms;

/** SMS gönderimi sırasındaki tüm hatalar bu tipe sarılır (CXF/JAX-WS tipleri dışarı sızmaz). */
public class ZeusSmsException extends RuntimeException {

    public ZeusSmsException(String message, Throwable cause) {
        super(message, cause);
    }
}
