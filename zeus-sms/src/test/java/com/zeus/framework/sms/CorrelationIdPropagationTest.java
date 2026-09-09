package com.zeus.framework.sms;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.jws.WebService;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.cxf.endpoint.Server;
import org.apache.cxf.jaxws.JaxWsServerFactoryBean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * MDC'deki correlation ID gerçekten SOAP header'ına giriyor mu — sunucu tarafında okunarak.
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
        // Gelen zarftaki header'ı yakalayan sunucu-tarafı interceptor.
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
    void mdcdekiKimlikSoapHeaderInaGirer() {
        MDC.put("correlationId", "abc123");
        try {
            ZeusSmsProperties p = new ZeusSmsProperties();
            p.setEndpoint(address);
            new ZeusSmsClient(p).send("905551112233", "merhaba");
        } finally {
            MDC.remove("correlationId");
        }
        assertThat(GORULEN.get()).isEqualTo("abc123");
    }

    @Test
    void mdcBoşkenSoapHeaderEklenmez() {
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
