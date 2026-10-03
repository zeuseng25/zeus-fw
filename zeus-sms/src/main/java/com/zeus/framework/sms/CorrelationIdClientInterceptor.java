package com.zeus.framework.sms;

import com.zeus.framework.correlation.CorrelationId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.apache.cxf.message.Message;
import org.apache.cxf.phase.AbstractPhaseInterceptor;
import org.apache.cxf.phase.Phase;

/**
 * Aktif correlation ID'yi giden SOAP çağrısının HTTP protokol header'ına ({@code
 * X-Correlation-Id}) basar.
 *
 * <p>Böylece çağrı zinciri SMS servisinin loglarında da aynı kimlikle izlenebilir
 * (bkz. {@code gelistirmeler/18-correlation-id.md}). Kimlik yoksa header eklenmez — uydurma
 * kimlik üretmek, izlemeyi kolaylaştırmak yerine yanıltırdı.
 *
 * <p><b>Neden zarfa değil protokol header'ına?</b> Framework'ün SOAP SUNUCU tarafı
 * ({@code zeus-soap} → {@code CorrelationIdSoapInterceptors.Inbound}) kimliği HTTP
 * header'ından okur; {@code Outbound} da oraya yazar. Kimlik SOAP zarfına
 * {@code <correlationId>} elemanı olarak konsaydı bir Zeus SOAP servisi bu istemciden gelen
 * çağrıda hiçbir kimlik GÖRMEZ, yenisini üretirdi — tasarımın hedefi framework'ün kendi
 * sunucularına karşı çalışmazdı. Ayrıca zarfa dokunmak WSDL sözleşmesini değiştirir ve
 * {@code mustUnderstand}'i katı denetleyen bir karşı taraf beklenmedik header'ı reddedebilir.
 *
 * <p><b>Neden {@code zeus-soap}'tan tekrar kullanılmıyor?</b> {@code zeus-soap}'a bağlanmak
 * standart tip bir uygulamaya {@code ZeusSoapEndpointRegistrar}'ı (endpoint YAYINLAMA
 * yeteneğini) da sürüklerdi; istemcinin buna ihtiyacı yok. Kopyalanan tek şey birkaç satırlık
 * mekanizmadır — <b>mekanizmanın kendisi</b> (protokol header'ı + {@link CorrelationId}
 * sabitleri) ortaktır ve ayrışmamalıdır.
 */
public class CorrelationIdClientInterceptor extends AbstractPhaseInterceptor<Message> {

    public CorrelationIdClientInterceptor() {
        // SETUP: protokol header'ları henüz yazılabilir durumdadır (zeus-soap Outbound ile aynı faz).
        super(Phase.SETUP);
    }

    @Override
    public void handleMessage(Message message) {
        String correlationId = CorrelationId.get();
        if (correlationId == null || correlationId.isBlank()) {
            return;
        }
        Map<String, List<String>> headers = protocolHeaders(message);
        // CXF header listelerini değiştirilebilir bekler → List.of kullanılmaz.
        headers.putIfAbsent(CorrelationId.HEADER_NAME, mutable(correlationId));
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

    private static List<String> mutable(String value) {
        List<String> list = new ArrayList<>(1);
        list.add(value);
        return list;
    }
}
