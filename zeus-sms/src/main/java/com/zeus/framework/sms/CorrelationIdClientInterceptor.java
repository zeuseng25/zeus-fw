package com.zeus.framework.sms;

import java.util.List;
import javax.xml.namespace.QName;
import org.apache.cxf.binding.soap.SoapMessage;
import org.apache.cxf.binding.soap.interceptor.AbstractSoapInterceptor;
import org.apache.cxf.headers.Header;
import org.apache.cxf.helpers.DOMUtils;
import org.apache.cxf.phase.Phase;
import org.slf4j.MDC;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * MDC'deki correlation ID'yi giden SOAP zarfının header'ına basar.
 *
 * <p>Böylece çağrı zinciri SMS servisinin loglarında da aynı kimlikle izlenebilir
 * (bkz. gelistirmeler/18-correlation-id.md). MDC boşsa header eklenmez — uydurma kimlik
 * üretmek, izlemeyi kolaylaştırmak yerine yanıltırdı.
 */
public class CorrelationIdClientInterceptor extends AbstractSoapInterceptor {

    private static final String NS = "http://sms.framework.zeus.com/";

    public CorrelationIdClientInterceptor() {
        super(Phase.PRE_PROTOCOL);
    }

    @Override
    public void handleMessage(SoapMessage message) {
        String id = MDC.get("correlationId");
        if (id == null || id.isBlank()) {
            return;
        }
        Document doc = DOMUtils.createDocument();
        Element el = doc.createElementNS(NS, "correlationId");
        el.setTextContent(id);
        List<Header> headers = message.getHeaders();
        headers.add(new Header(new QName(NS, "correlationId"), el));
    }
}
