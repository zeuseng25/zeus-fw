package com.zeus.framework.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * {@link ZeusAiException}'ı RFC 7807 {@link ProblemDetail} olarak 502'ye çevirir.
 *
 * <p>zeus-base'teki {@code GlobalExceptionHandler} ile aynı deseni izler; AI'a özgü olduğu
 * için ayrı advice'tır ve yalnızca zeus-ai bağımlılığı ekleyen uygulamalarda devreye girer.
 * Sağlayıcıdan dönen ham hata mesajı istemciye sızdırılmaz (anahtar/URL içerebilir), loga yazılır.
 */
@RestControllerAdvice
public class ZeusAiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ZeusAiExceptionHandler.class);

    @ExceptionHandler(ZeusAiException.class)
    public ProblemDetail handleAiFailure(ZeusAiException ex) {
        log.error("AI çağrısı başarısız: {}", ex.getMessage(), ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_GATEWAY, "AI servisi şu anda yanıt veremiyor.");
        problem.setTitle("AI servisi hatası");
        return problem;
    }
}
