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
 *
 * <p>Repo konvansiyonuna göre {@code *Test} adlandırılır ve {@code mvn test}'in standart
 * Surefire taramasıyla koşar — ayrı bir {@code *IT}/failsafe fazına gerek yok, çünkü altyapı
 * yerel bir Jetty sunucusudur ve hızlıdır.
 */
class ZeusSmsClientTest {

    private static Server server;
    private static String address;

    private static Server yavasServer;
    private static String yavasAddress;

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

    /** {@code receiveTimeout}'un GERÇEKTEN dolmasını tetiklemek için bilerek yavaş yanıt verir. */
    @WebService(endpointInterface = "com.zeus.framework.sms.SmsService",
                targetNamespace = "http://sms.framework.zeus.com/")
    public static class YavasSmsService implements SmsService {
        @Override
        public String sendSms(String to, String text) {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "MSG-YAVAS";
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

        yavasAddress = "http://localhost:18082/sms-yavas";
        JaxWsServerFactoryBean yavasFactory = new JaxWsServerFactoryBean();
        yavasFactory.setServiceClass(SmsService.class);
        yavasFactory.setAddress(yavasAddress);
        yavasFactory.setServiceBean(new YavasSmsService());
        yavasServer = yavasFactory.create();
    }

    @AfterAll
    static void kapat() {
        if (server != null) {
            server.destroy();
        }
        if (yavasServer != null) {
            yavasServer.destroy();
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

    /**
     * {@code receiveTimeout}'un GERÇEKTEN uygulandığını kanıtlar: sunucu 2 saniye uyur,
     * istemcinin {@code receiveTimeout}'u 300ms'dir. `ZeusSmsClient`'taki
     * {@code conduit.setClient(policy)} satırı silinirse bu test kırmızıya döner (elapsed
     * ~2000ms'ye çıkar) — eski test (bağlantı reddi) bu regresyonu YAKALAMIYORDU.
     */
    @Test
    void yanitZamanAsimiGercektenUygulaniyor() {
        ZeusSmsProperties p = new ZeusSmsProperties();
        p.setEndpoint(yavasAddress);
        p.setConnectTimeout(Duration.ofSeconds(5));
        p.setReceiveTimeout(Duration.ofMillis(300));
        ZeusSmsClient client = new ZeusSmsClient(p);

        long baslangic = System.nanoTime();
        assertThatThrownBy(() -> client.send("905551112233", "merhaba"))
                .isInstanceOf(ZeusSmsException.class);
        long gecenMs = Duration.ofNanos(System.nanoTime() - baslangic).toMillis();

        // Sunucu 2000ms uyuyor; policy uygulanmasaydı çağrı en az o kadar sürerdi.
        // 300ms sınırına yakın kesildiğini doğruluyoruz (CI gürültüsü için cömert üst sınır).
        assertThat(gecenMs).isLessThan(1500);
    }
}
