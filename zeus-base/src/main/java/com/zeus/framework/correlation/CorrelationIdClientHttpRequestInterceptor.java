package com.zeus.framework.correlation;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/**
 * Giden HTTP çağrılarına correlation ID header'ını ekler.
 *
 * <p>Zincirin servisler arası halkasını bu sınıf kurar: A uygulamasının REST ucuna gelen
 * istek B'yi çağırdığında, B'nin filtresi header'ı görüp aynı kimlikle devam eder. Böylece
 * "REST → başka API → onun backend'i" akışının tamamı tek kimlikle izlenir.
 *
 * <p>Mevcut bir header'ın üzerine YAZMAZ: çağıran kod bilinçli olarak kendi kimliğini
 * koyduysa ona dokunulmaz.
 */
public class CorrelationIdClientHttpRequestInterceptor implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        String correlationId = CorrelationId.get();
        // Spring Framework 7: HttpHeaders artık MultiValueMap değil → containsHeader kullanılır.
        if (correlationId != null && !request.getHeaders().containsHeader(CorrelationId.HEADER_NAME)) {
            request.getHeaders().add(CorrelationId.HEADER_NAME, correlationId);
        }
        return execution.execute(request, body);
    }
}
