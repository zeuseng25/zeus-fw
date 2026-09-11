package com.zeus.framework.sms;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.cxf.jaxws.JaxWsProxyFactoryBean;
import org.junit.jupiter.api.Test;

/**
 * Spec Risk 4'ün ölçümü: zeus.soap.enabled yazılmamış bir uygulamada CXF autoconfig'leri
 * veto edilir. SMS İSTEMCİSİ o durumda da çalışmalı — çünkü zeus-sms, zeus-soap'a bağlı
 * değildir ve proxy'yi kendisi kurar. Bu test Spring context'i HİÇ kurmaz; tam olarak
 * "autoconfig yok" durumunu temsil eder.
 */
class CxfAutoConfigYokkenIstemciTest {

    @Test
    void springYokkenDeProxyKurulur() {
        JaxWsProxyFactoryBean f = new JaxWsProxyFactoryBean();
        f.setServiceClass(SmsService.class);
        f.setAddress("http://localhost:1/sms");

        SmsService proxy = (SmsService) f.create();

        // Proxy kuruldu: CXF varsayılan Bus'ı kendi oluşturdu, Spring'e ihtiyaç duymadı.
        assertThat(proxy).isNotNull();
        assertThat(org.apache.cxf.frontend.ClientProxy.getClient(proxy)).isNotNull();
    }
}
