package com.zeus.framework.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.jws.WebService;
import java.time.Duration;
import org.apache.cxf.endpoint.Server;
import org.apache.cxf.jaxws.JaxWsServerFactoryBean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * GERÇEK bir CXF endpoint'ine karşı tam SOAP turu — mock yok. Serileştirme, bus ve HTTP
 * transport gerçekten çalışır; spike'ın kanıtlayamadığı kısım budur.
 */
class ZeusSmsClientIT {

    private static Server server;
    private static String address;

    @WebService(endpointInterface = "com.zeus.framework.sms.SmsService",
                targetNamespace = "http://sms.framework.zeus.com/")
    public static class StubSmsService implements SmsService {
        @Override
        public String sendSms(String to, String text) {
            if (to == null || to.isBlank()) {
                throw new IllegalArgumentException("alıcı boş");
            }
            return "MSG-" + to + "-" + text.length();
        }
    }

    @BeforeAll
    static void ayagaKaldir() {
        address = "http://localhost:18081/sms";
        JaxWsServerFactoryBean f = new JaxWsServerFactoryBean();
        f.setServiceClass(SmsService.class);
        f.setAddress(address);
        f.setServiceBean(new StubSmsService());
        server = f.create();
    }

    @AfterAll
    static void kapat() {
        if (server != null) {
            server.destroy();
        }
    }

    @Test
    void gercekSoapTuruAtar() {
        ZeusSmsProperties p = new ZeusSmsProperties();
        p.setEndpoint(address);
        ZeusSmsClient client = new ZeusSmsClient(p);

        assertThat(client.send("905551112233", "merhaba")).isEqualTo("MSG-905551112233-7");
    }

    @Test
    void servisHatasiZeusSmsExceptionAySarilir() {
        ZeusSmsProperties p = new ZeusSmsProperties();
        p.setEndpoint(address);
        ZeusSmsClient client = new ZeusSmsClient(p);

        assertThatThrownBy(() -> client.send("", "merhaba"))
                .isInstanceOf(ZeusSmsException.class)
                .hasMessageContaining("SMS gönderilemedi");
    }

    @Test
    void ulasilamayanEndpointZeusSmsExceptionAySarilir() {
        ZeusSmsProperties p = new ZeusSmsProperties();
        p.setEndpoint("http://localhost:18099/yok");
        p.setConnectTimeout(Duration.ofMillis(300));
        p.setReceiveTimeout(Duration.ofMillis(300));
        ZeusSmsClient client = new ZeusSmsClient(p);

        assertThatThrownBy(() -> client.send("905551112233", "merhaba"))
                .isInstanceOf(ZeusSmsException.class);
    }
}
