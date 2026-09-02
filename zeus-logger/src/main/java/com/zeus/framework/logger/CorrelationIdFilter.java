package com.zeus.framework.logger;

import com.zeus.framework.correlation.CorrelationId;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Correlation ID'nin yaşam döngüsünü yöneten filtre — izleme zincirinin giriş kapısı.
 *
 * <p>Her istekte:
 * <ol>
 *   <li>{@code X-Correlation-Id} header'ı varsa doğrulanıp kullanılır (zincirin devamı),
 *       yoksa yeni kimlik üretilir (zincirin başlangıcı).</li>
 *   <li>MDC'ye yazılır → o istekteki TÜM log satırları (controller, service, zeus-database'in
 *       stored procedure çağrıları dahil) aynı kimliği taşır.</li>
 *   <li>Yanıt header'ına da konur → çağıran taraf/istemci kimliği görür, destek ekibi
 *       kullanıcıdan gelen tek bir değerle tüm zinciri arayabilir.</li>
 *   <li>{@code finally} bloğunda temizlenir.</li>
 * </ol>
 *
 * <p><b>Temizlik neden kritik:</b> WildFly/Tomcat thread'leri havuzdan gelir. MDC
 * temizlenmezse bir sonraki istek, kendisine ait olmayan bir correlation ID ile loglanır —
 * sessiz ve teşhisi çok zor bir hata.
 *
 * <p><b>Sıra:</b> {@link ZeusLoggerAutoConfiguration} bu filtreyi {@link RequestLoggingFilter}'dan
 * ÖNCE çalışacak şekilde kaydeder; aksi halde isteğin özet log satırı kimliksiz basılır.
 */
public class CorrelationIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // Dışarıdan gelen değer istemci kontrolündedir → sanitize (log injection koruması).
        String correlationId = CorrelationId.sanitizeOrGenerate(
                request.getHeader(CorrelationId.HEADER_NAME));
        CorrelationId.set(correlationId);
        response.setHeader(CorrelationId.HEADER_NAME, correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            CorrelationId.clear();
        }
    }
}
