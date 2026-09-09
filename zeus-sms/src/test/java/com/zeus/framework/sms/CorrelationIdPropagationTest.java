package com.zeus.framework.sms;

import static org.assertj.core.api.Assertions.assertThat;

import com.zeus.framework.correlation.CorrelationId;
import jakarta.jws.WebService;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.cxf.endpoint.Server;
import org.apache.cxf.jaxws.JaxWsServerFactoryBean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * Aktif correlation ID gerçekten giden çağrının HTTP protokol header'ına giriyor mu —
 * SUNUCU tarafında, {@code zeus-soap}'ın {@code Inbound} interceptor'ı ile AYNI yoldan
 * okunarak (bkz. {@link HeaderYakalayici}).
 *
 * <p>Taşıyıcı bilinçli olarak SOAP zarfı DEĞİL, {@code X-Correlation-Id} protokol
 * header'ıdır: framework'ün kendi SOAP sunucuları kimliği oradan okur, zarftan değil
 * (final review, Important 3).
 *
 * <p>Repo konvansiyonuna göre {@code *Test} adlandırılır (bkz. {@code ZeusSmsClientTest}) ve
 * standart Surefire taramasıyla koşar; {@code *IT} kullanılmaz çünkü failsafe yapılandırılmamış
 * ve surefire onu taramaz — sessizce hiç koşmayan bir test üretirdi.
 */
class CorrelationIdPropagationTest {

    private static Server server;
    private static String address;
    private static final AtomicReference<String> GORULEN = new AtomicReference<>();

    @WebService(endpointInterface = "com.zeus.framework.sms.SmsService",
                targetNamespace = "http://sms.framework.zeus.com/")
    public static class StubSmsService implements SmsService {
        @Override
        public String sendSms(String to, String text) {
            return "OK";
        }
    }

    @BeforeAll
    static void ayagaKaldir() {
        // 18083: ZeusSmsClientTest 18081 (stub) ve 18082 (yavaş) portlarını kullanıyor.
        address = "http://localhost:18083/sms";
        JaxWsServerFactoryBean f = new JaxWsServerFactoryBean();
        f.setServiceClass(SmsService.class);
        f.setAddress(address);
        f.setServiceBean(new StubSmsService());
        server = f.create();
        // Gelen isteğin protokol header'larını yakalayan sunucu-tarafı interceptor.
        server.getEndpoint().getInInterceptors().add(new HeaderYakalayici(GORULEN));
    }

    @AfterAll
    static void kapat() {
        if (server != null) {
            server.destroy();
        }
        MDC.clear();
    }

    @Test
    void aktifKimlikProtokolHeaderInaGirer() {
        // Anahtar ELLE yazılmaz: CorrelationId sabitleri tek kaynaktır.
        CorrelationId.set("abc123");
        try {
            ZeusSmsProperties p = new ZeusSmsProperties();
            p.setEndpoint(address);
            new ZeusSmsClient(p).send("905551112233", "merhaba");
        } finally {
            CorrelationId.clear();
        }
        assertThat(GORULEN.get()).isEqualTo("abc123");
    }

    @Test
    void kimlikYokkenHeaderEklenmez() {
        // MDC'yi temizle — hiçbir correlation ID olmamalı
        MDC.clear();
        // Önceki testten kalan değeri sıfırla (testler aynı statik AtomicReference'ı paylaşıyor)
        GORULEN.set(null);
        try {
            ZeusSmsProperties p = new ZeusSmsProperties();
            p.setEndpoint(address);
            new ZeusSmsClient(p).send("905551112233", "merhaba");
        } finally {
            MDC.clear();
        }
        // Hiçbir header eklenmediğini doğrula
        assertThat(GORULEN.get()).isNull();
    }
}
