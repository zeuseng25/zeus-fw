package com.zeus.framework.sms;

import com.zeus.framework.correlation.CorrelationId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.cxf.message.Message;
import org.apache.cxf.phase.AbstractPhaseInterceptor;
import org.apache.cxf.phase.Phase;

/**
 * Test yardımcısı: SUNUCU tarafında gelen isteğin HTTP protokol header'larından
 * {@link CorrelationId#HEADER_NAME} değerini yakalar.
 *
 * <p>Okuma biçimi {@code zeus-soap}'ın {@code CorrelationIdSoapInterceptors.Inbound}'u ile
 * BİREBİR aynıdır ({@link Phase#RECEIVE} fazı + {@link Message#PROTOCOL_HEADERS} +
 * büyük/küçük harf duyarsız arama); yani bu testin yeşil olması, gerçek bir Zeus SOAP
 * sunucusunun bu istemciden gelen kimliği okuyabileceğinin kanıtıdır.
 */
class HeaderYakalayici extends AbstractPhaseInterceptor<Message> {

    private final AtomicReference<String> hedef;

    HeaderYakalayici(AtomicReference<String> hedef) {
        super(Phase.RECEIVE);
        this.hedef = hedef;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void handleMessage(Message message) {
        Map<String, List<String>> headers =
                (Map<String, List<String>>) message.get(Message.PROTOCOL_HEADERS);
        if (headers == null) {
            return;
        }
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            // HTTP header adları büyük/küçük harf duyarsızdır; taşıma katmanı adı
            // normalize edebilir, bu yüzden equalsIgnoreCase ile aranır.
            if (CorrelationId.HEADER_NAME.equalsIgnoreCase(e.getKey())
                    && e.getValue() != null && !e.getValue().isEmpty()) {
                hedef.set(e.getValue().get(0));
                return;
            }
        }
    }
}
