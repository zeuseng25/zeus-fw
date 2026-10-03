package com.zeus.framework.soap;

import jakarta.jws.WebService;
import org.apache.cxf.Bus;
import org.apache.cxf.jaxws.EndpointImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;

import java.util.Map;

/**
 * {@code @WebService} bean'lerini CXF bus'ına otomatik yayınlar.
 *
 * <p>Uygulama yalnızca {@code @WebService} işaretli bir {@code @Component}/{@code @Service}
 * bean'i tanımlar; endpoint {@code /services/<beanAdı>} altında otomatik yayınlanır
 * (CXFServlet path'i {@code cxf.path} property'siyle değişebilir). Farklı bir adres
 * isteyen uygulama kendi {@link EndpointImpl} bean'ini tanımlayarak bu otomatiğin
 * dışına çıkabilir (registrar aynı implementor'ı ikinci kez yayınlamaz — kendi endpoint
 * bean'ini tanımlayan uygulama için {@code zeus.soap.auto-publish=false} ile tamamen
 * kapatılabilir; property işleme şimdilik iskelet kapsamı dışındadır).
 */
public class ZeusSoapEndpointRegistrar implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(ZeusSoapEndpointRegistrar.class);

    private final Bus bus;
    private final ApplicationContext context;

    public ZeusSoapEndpointRegistrar(Bus bus, ApplicationContext context) {
        this.bus = bus;
        this.context = context;
    }

    @Override
    public void afterSingletonsInstantiated() {
        Map<String, Object> services = context.getBeansWithAnnotation(WebService.class);
        services.forEach((beanName, bean) -> {
            // EndpointImpl bean'leri zaten yayın nesnesidir; ikinci kez sarmalanmaz.
            if (bean instanceof EndpointImpl) {
                return;
            }
            EndpointImpl endpoint = new EndpointImpl(bus, bean);
            endpoint.publish("/" + beanName);
            log.info("Zeus SOAP: endpoint yayınlandı -> /{} ({})", beanName, bean.getClass().getSimpleName());
        });
        if (services.isEmpty()) {
            log.info("Zeus SOAP: yayınlanacak @WebService bean'i bulunamadı.");
        }
    }
}
