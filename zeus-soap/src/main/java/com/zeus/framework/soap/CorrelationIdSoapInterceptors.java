package com.zeus.framework.soap;

import com.zeus.framework.correlation.CorrelationId;
import org.apache.cxf.message.Message;
import org.apache.cxf.phase.AbstractPhaseInterceptor;
import org.apache.cxf.phase.Phase;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * SOAP hattında correlation ID taşıyan CXF interceptor'ları.
 *
 * <p>REST tarafındaki {@code CorrelationIdFilter}'ın SOAP karşılığıdır. Kimlik yine HTTP
 * protokol header'ında ({@code X-Correlation-Id}) taşınır — SOAP zarfına dokunulmaz, yani
 * WSDL sözleşmesi değişmez ve mevcut istemciler etkilenmez.
 *
 * <p>Karma bir zincirde (REST → SOAP → REST) kimlik kesintisiz akar.
 */
public final class CorrelationIdSoapInterceptors {

    private CorrelationIdSoapInterceptors() {
    }

    /**
     * Gelen SOAP isteğinde correlation ID'yi kurar.
     *
     * <p>{@link Phase#RECEIVE} fazı: mesaj işlenmeden önce çalışır, böylece servis
     * implementasyonunun ve zeus-database'in tüm logları kimliği taşır.
     *
     * <p><b>Not:</b> Bir servlet konteynerinde çalışıldığında {@code CorrelationIdFilter}
     * zaten kimliği kurmuş olur; bu interceptor o durumda üzerine yazmaz. CXF'in servlet
     * dışı taşımalarında (JMS vb.) tek kurucu budur.
     */
    public static class Inbound extends AbstractPhaseInterceptor<Message> {

        public Inbound() {
            super(Phase.RECEIVE);
        }

        @Override
        public void handleMessage(Message message) {
            if (CorrelationId.get() != null) {
                return; // Servlet filtresi zaten kurmuş.
            }
            String incoming = firstHeader(message, CorrelationId.HEADER_NAME);
            CorrelationId.set(CorrelationId.sanitizeOrGenerate(incoming));
        }
    }

    /**
     * Giden SOAP çağrılarına (istemci tarafı) correlation ID header'ını ekler.
     *
     * <p>{@link Phase#SETUP} fazı: protokol header'ları henüz yazılabilir durumdadır.
     */
    public static class Outbound extends AbstractPhaseInterceptor<Message> {

        public Outbound() {
            super(Phase.SETUP);
        }

        @Override
        public void handleMessage(Message message) {
            String correlationId = CorrelationId.get();
            if (correlationId == null) {
                return;
            }
            Map<String, List<String>> headers = protocolHeaders(message);
            // CXF header listelerini değiştirilebilir bekler → List.of kullanılmaz.
            headers.putIfAbsent(CorrelationId.HEADER_NAME, mutable(correlationId));
        }
    }

    private static String firstHeader(Message message, String name) {
        Map<String, List<String>> headers = protocolHeaders(message);
        List<String> values = headers.get(name);
        return (values == null || values.isEmpty()) ? null : values.get(0);
    }

    /**
     * Mesajın HTTP protokol header'ları; yoksa oluşturulup mesaja bağlanır.
     *
     * <p>Header adları büyük/küçük harf duyarsız karşılaştırılmalıdır — CXF bu haritayı
     * {@code TreeMap(String.CASE_INSENSITIVE_ORDER)} ile kurar, biz de yoksa aynısını kurarız.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, List<String>> protocolHeaders(Message message) {
        Map<String, List<String>> headers =
                (Map<String, List<String>>) message.get(Message.PROTOCOL_HEADERS);
        if (headers == null) {
            headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            message.put(Message.PROTOCOL_HEADERS, headers);
        }
        return headers;
    }

    /** CXF'in beklediği değiştirilebilir liste tipi için yardımcı. */
    static List<String> mutable(String value) {
        List<String> list = new ArrayList<>(1);
        list.add(value);
        return list;
    }
}
