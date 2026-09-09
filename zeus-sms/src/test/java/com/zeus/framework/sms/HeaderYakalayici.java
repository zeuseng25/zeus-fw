package com.zeus.framework.sms;

import java.util.concurrent.atomic.AtomicReference;
import org.apache.cxf.binding.soap.SoapMessage;
import org.apache.cxf.binding.soap.interceptor.AbstractSoapInterceptor;
import org.apache.cxf.headers.Header;
import org.apache.cxf.phase.Phase;
import org.w3c.dom.Element;

/** Test yardımcısı: gelen zarftaki correlationId header'ını yakalar. */
class HeaderYakalayici extends AbstractSoapInterceptor {

    private final AtomicReference<String> hedef;

    HeaderYakalayici(AtomicReference<String> hedef) {
        super(Phase.PRE_PROTOCOL);
        this.hedef = hedef;
    }

    @Override
    public void handleMessage(SoapMessage message) {
        for (Header h : message.getHeaders()) {
            if ("correlationId".equals(h.getName().getLocalPart())) {
                hedef.set(((Element) h.getObject()).getTextContent());
            }
        }
    }
}
