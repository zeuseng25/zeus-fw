package com.zeus.framework.soap;

import org.apache.cxf.Bus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

/**
 * Zeus SOAP modülü auto-configuration.
 *
 * <p>CXF starter'ın kendi auto-config'i ({@code CxfAutoConfiguration}) {@code CXFServlet}'i
 * ({@code cxf.path}, varsayılan {@code /services}) ve {@link Bus}'ı kurar. Bu sınıf onun
 * üstüne yalnızca zeus katkısını ekler: {@code @WebService} bean'lerini otomatik yayınlayan
 * {@link ZeusSoapEndpointRegistrar}.
 *
 * <p>SOAP tipi uygulamalarda (parent: {@code zeus-soap-parent}) WildFly'ın kendi JBossWS/CXF'i
 * descriptor'daki {@code webservices} subsystem dışlamasıyla devre dışıdır; buradaki CXF,
 * {@code com.zeus.soap} module'ünden yüklenir.
 */
@AutoConfiguration
@ConditionalOnClass(Bus.class)
public class ZeusSoapAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ZeusSoapAutoConfiguration.class);

    public ZeusSoapAutoConfiguration() {
        log.info("Zeus SOAP modülü yüklendi (CXF/JAX-WS).");
    }

    @Bean
    @ConditionalOnMissingBean
    public ZeusSoapEndpointRegistrar zeusSoapEndpointRegistrar(Bus bus, ApplicationContext context) {
        return new ZeusSoapEndpointRegistrar(bus, context);
    }

    /**
     * Correlation ID'yi SOAP hattına bağlar: gelen isteklerde kurar, giden çağrılarda taşır.
     *
     * <p>Interceptor'lar {@link Bus}'a eklendiği için hem {@code @WebService} sunucu
     * uçlarında hem de CXF istemcilerinde geçerlidir.
     */
    @Bean
    @ConditionalOnProperty(prefix = "zeus.correlation", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    public InitializingBean zeusSoapCorrelationInterceptors(Bus bus) {
        return () -> {
            bus.getInInterceptors().add(new CorrelationIdSoapInterceptors.Inbound());
            bus.getOutInterceptors().add(new CorrelationIdSoapInterceptors.Outbound());
            log.info("Zeus SOAP: correlation ID interceptor'ları CXF Bus'a eklendi.");
        };
    }
}
