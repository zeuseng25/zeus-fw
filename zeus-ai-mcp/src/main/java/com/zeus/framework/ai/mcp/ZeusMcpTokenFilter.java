package com.zeus.framework.ai.mcp;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * MCP ucunu paylaşılan sır ile koruyan filtre.
 *
 * <p><b>Korunan yol, yayınlanan yoldan türetilir.</b> Filtre uç yolunu kendi property'sinden
 * DEĞİL, taşımanın okuduğu {@code spring.ai.mcp.server.streamable-http.mcp-endpoint} değerinden
 * alır. Böylece "korunan yol" ile "yayınlanan yol"un ayrışması yapısal olarak imkânsızdır —
 * zeus tarafında ikinci bir uç property'si TANIMLANMAMASININ sebebi budur.
 *
 * <p><b>Sıra.</b> {@code HIGHEST_PRECEDENCE + 20}: {@code CorrelationIdFilter}
 * ({@code HIGHEST_PRECEDENCE}) ve {@code RequestLoggingFilter} ({@code +10}) ÖNCE çalışır, böylece
 * reddedilen bir çağrı da correlation ID alır ve istek logunda iz bırakır.
 *
 * <p><b>Oracle yok.</b> Eksik header ile hatalı header birebir aynı {@code 401} gövdesini alır;
 * ayrımı yalnızca log tutar. Karşılaştırma sabit zamanlıdır. Token ne loglanır ne de yanıta girer.
 *
 * <p>Not: bu filtre erişim denetiminin TAMAMI değildir — yetenek kapalıyken uç hiç yayınlanmaz
 * ve token boşken açılış reddedilir ({@link ZeusMcpAutoConfiguration}). Bu filtre, uç yayındayken
 * çağıranın sırrı bilmesini şart koşar.
 */
public class ZeusMcpTokenFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ZeusMcpTokenFilter.class);

    private final String korunanYol;
    private final String headerAdi;
    private final byte[] beklenenToken;

    public ZeusMcpTokenFilter(String korunanYol, ZeusMcpProperties properties) {
        this.korunanYol = korunanYol;
        this.headerAdi = properties.getTokenHeader();
        this.beklenenToken = properties.getToken().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Yalnız MCP ucu korunur; uygulamanın kendi uçlarına dokunulmaz.
        String yol = request.getServletPath();
        if (yol == null || yol.isEmpty()) {
            yol = request.getRequestURI();
        }
        return !korunanYol.equals(yol);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String sunulan = request.getHeader(headerAdi);
        if (sunulan == null || sunulan.isBlank()) {
            // Ayrım YALNIZ logda: istemci iki durumu birbirinden ayırt edemez.
            log.warn("MCP erişimi reddedildi — anahtar yok (header '{}' gelmedi), yol={}",
                    headerAdi, korunanYol);
            reddet(response);
            return;
        }
        if (!MessageDigest.isEqual(beklenenToken, sunulan.getBytes(StandardCharsets.UTF_8))) {
            log.warn("MCP erişimi reddedildi — anahtar hatalı, yol={}", korunanYol);
            reddet(response);
            return;
        }
        filterChain.doFilter(request, response);
    }

    /**
     * RFC 7807 gövdesi elle yazılır: filtre {@code DispatcherServlet}'ten önce çalıştığı için
     * hiçbir {@code @RestControllerAdvice} buraya ulaşamaz. Alan disiplini (başlık + ayrıntı,
     * iç detay sızdırmama) {@code ZeusAiExceptionHandler}'dan birebir alınmıştır.
     */
    private void reddet(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("""
                {"type":"about:blank","title":"MCP erişimi reddedildi",\
                "status":401,"detail":"Geçerli bir erişim anahtarı gerekli."}""");
    }
}
