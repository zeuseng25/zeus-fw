package com.zeus.framework.sms;

import org.apache.cxf.endpoint.Client;
import org.apache.cxf.frontend.ClientProxy;
import org.apache.cxf.jaxws.JaxWsProxyFactoryBean;
import org.apache.cxf.transport.http.HTTPConduit;
import org.apache.cxf.transports.http.configuration.HTTPClientPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SMS SOAP istemcisi. Proxy bir kez kurulur ve yeniden kullanılır (CXF proxy'si thread-safe'dir).
 *
 * <p>Zaman aşımları AÇIKÇA verilir: CXF'in varsayılanları 30s bağlantı / 60s yanıttır; bir
 * istek işleyen thread'i bir dakika tutabilecek bu değerler bir SMS çağrısı için fazlasıyla
 * uzundur, bu yüzden açıkça kısaltılır.
 */
public class ZeusSmsClient {

    private static final Logger log = LoggerFactory.getLogger(ZeusSmsClient.class);

    private final SmsService proxy;

    public ZeusSmsClient(ZeusSmsProperties props) {
        JaxWsProxyFactoryBean factory = new JaxWsProxyFactoryBean();
        factory.setServiceClass(SmsService.class);
        factory.setAddress(props.getEndpoint());
        if (props.getUsername() != null) {
            factory.setUsername(props.getUsername());
            factory.setPassword(props.getPassword());
        }
        // Aktif correlation ID'yi giden çağrının X-Correlation-Id protokol header'ına basar
        // (bkz. gelistirmeler/18-correlation-id.md); SOAP zarfına dokunulmaz.
        // create() ÖNCESİ eklenmeli: factory'nin bu listesi proxy oluşturulurken okunur.
        factory.getOutInterceptors().add(new CorrelationIdClientInterceptor());
        this.proxy = (SmsService) factory.create();

        Client client = ClientProxy.getClient(this.proxy);
        HTTPConduit conduit = (HTTPConduit) client.getConduit();
        HTTPClientPolicy policy = new HTTPClientPolicy();
        policy.setConnectionTimeout(props.getConnectTimeout().toMillis());
        policy.setReceiveTimeout(props.getReceiveTimeout().toMillis());
        conduit.setClient(policy);

        log.info("Zeus SMS: istemci kuruldu (endpoint={}, connect={}, receive={}).",
                props.getEndpoint(), props.getConnectTimeout(), props.getReceiveTimeout());
    }

    /**
     * SMS gönderir.
     *
     * @return servis tarafından üretilen mesaj kimliği
     * @throws ZeusSmsException her türlü gönderim hatasında (CXF/JAX-WS tipleri sarılır)
     */
    public String send(String to, String text) {
        try {
            return proxy.sendSms(to, text);
        } catch (RuntimeException e) {
            throw new ZeusSmsException("SMS gönderilemedi (alıcı=" + to + ")", e);
        }
    }
}
