package com.zeus.framework.logger;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Her HTTP isteğini tek satırda loglar: metot, yol, durum kodu ve süre (ms).
 *
 * <p>Framework'ün "log atma" yeteneğinin temel parçası; uygulamalar ekstra kod yazmadan
 * istek loglarına sahip olur. {@link ZeusLoggerAutoConfiguration} tarafından kaydedilir.
 */
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long start = System.currentTimeMillis();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long took = System.currentTimeMillis() - start;
            String query = request.getQueryString();
            log.info("{} {}{} -> {} ({} ms)",
                    request.getMethod(),
                    request.getRequestURI(),
                    query != null ? "?" + query : "",
                    response.getStatus(),
                    took);
        }
    }
}
