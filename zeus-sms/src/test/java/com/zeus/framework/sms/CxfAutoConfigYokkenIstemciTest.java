package com.zeus.framework.sms;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.cxf.endpoint.Client;
import org.apache.cxf.frontend.ClientProxy;
import org.apache.cxf.transport.http.HTTPConduit;
import org.apache.cxf.transports.http.configuration.HTTPClientPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Spec Risk 4'ün ölçümü: {@code zeus.soap.enabled} yazılmamış bir uygulamada CXF
 * autoconfig'leri ({@code org.apache.cxf.spring.boot.autoconfigure.*}) veto edilir.
 * SMS İSTEMCİSİ o durumda da çalışmalı — çünkü {@code zeus-sms}, {@code zeus-soap}'a
 * bağlı değildir ve proxy'yi (ve giden zaman aşımı/interceptor kurulumunu) kendisi yapar.
 *
 * <p>Bu test Spring context'i HİÇ kurmaz; "autoconfig yok" durumunu birebir temsil eder.
 * Sadece proxy'nin OLUŞMASINI değil, {@link ZeusSmsClient}'ın kurucusunun yaptığı ÜÇ işin
 * de gerçekten tamamlandığını kanıtlar (kurucudaki sıraya göre):
 * <ol>
 *   <li>{@code CorrelationIdClientInterceptor} giden interceptor zincirine eklenmiş,</li>
 *   <li>{@code ClientProxy.getClient(proxy).getConduit()} bir {@code HTTPConduit}'e cast
 *       edilebilmiş (bus üzerinden conduit seçimi — normalde CXF autoconfig'inin
 *       hazırladığı altyapı, burada Spring hiç yokken de çalışıyor),</li>
 *   <li>o conduit'e {@code ZeusSmsProperties}'ten gelen zaman aşımlarını taşıyan bir
 *       {@code HTTPClientPolicy} GERÇEKTEN bağlanmış.</li>
 * </ol>
 *
 * <p>Ağ çağrısına gerek yok: kurucunun kendisi bu üç satırı çalıştırıyor. Gerçek bir SOAP
 * turunun (ve {@code receiveTimeout}'un fiilen uygulandığının) kanıtı zaten
 * {@link ZeusSmsClientTest}'te var — o test gerçek bir Jetty endpoint'ine karşı gerçek bir
 * çağrı yapıyor, yine Spring context'i olmadan. Bu ikisi birlikte Risk 4'ün ölçümünü
 * oluşturur; burada ikinci bir uçtan uca test tekrar edilmiyor.
 *
 * <p>{@code proxy} alanına erişim: {@link ZeusSmsClient} bunu kasıtlı olarak private tutar
 * (üretim kodu bu testin ihtiyacı için genişletilmedi); {@link ReflectionTestUtils} ile aynı
 * alana erişip üretim kodunun izlediği YOLU ({@code ClientProxy.getClient(proxy)}) birebir
 * tekrar ediyoruz.
 */
class CxfAutoConfigYokkenIstemciTest {

    @Test
    void konstruktorunTumYoluSpringOlmadanCalisirVeGercekDegerleriBaglar() {
        ZeusSmsProperties props = new ZeusSmsProperties();
        // Adres hiç çağrılmıyor — yalnızca kurulum ölçülüyor, ağ trafiği yok.
        props.setEndpoint("http://localhost:1/sms");

        // Spring context YOK: CxfAutoConfiguration / CxfJaxwsAutoConfiguration hiç devreye
        // girmiyor. Yine de ZeusSmsClient'ın kurucusu baştan sona çalışmalı.
        ZeusSmsClient client = new ZeusSmsClient(props);

        SmsService proxy = (SmsService) ReflectionTestUtils.getField(client, "proxy");
        Client cxfClient = ClientProxy.getClient(proxy);

        // 1) CorrelationIdClientInterceptor gerçekten eklenmiş (kurucunun ilk işi).
        boolean korelasyonInterceptorVar = cxfClient.getOutInterceptors().stream()
                .anyMatch(i -> i instanceof CorrelationIdClientInterceptor);
        assertThat(korelasyonInterceptorVar)
                .as("CorrelationIdClientInterceptor giden zincire eklenmemiş")
                .isTrue();

        // 2) Conduit bus üzerinden seçilmiş ve HTTPConduit'e cast tutmuş (kurucunun ikinci
        //    işi) — bu adım normalde CXF autoconfig'inin kurduğu altyapıya dayanır.
        HTTPConduit conduit = (HTTPConduit) cxfClient.getConduit();
        assertThat(conduit).isNotNull();

        // 3) O conduit'e ZeusSmsProperties'ten gelen GERÇEK zaman aşımlarını taşıyan policy
        //    bağlanmış (kurucunun üçüncü işi) — varsayılanlar: connect 5s, receive 15s.
        HTTPClientPolicy policy = conduit.getClient();
        assertThat(policy.getConnectionTimeout())
                .as("connectTimeout conduit'e bağlanmamış")
                .isEqualTo(props.getConnectTimeout().toMillis())
                .isEqualTo(5000L);
        assertThat(policy.getReceiveTimeout())
                .as("receiveTimeout conduit'e bağlanmamış")
                .isEqualTo(props.getReceiveTimeout().toMillis())
                .isEqualTo(15000L);
    }
}
