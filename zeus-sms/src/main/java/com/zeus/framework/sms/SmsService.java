package com.zeus.framework.sms;

import jakarta.jws.WebMethod;
import jakarta.jws.WebParam;
import jakarta.jws.WebService;

/**
 * SMS servisinin SOAP arayüzü (SEI).
 *
 * <p>WSDL'den ÜRETİLMEZ, elle yazılır: repoya WSDL koymak ve build'e kod üretme eklentisi
 * sokmak, framework'ün derlenmesini dış bir sözleşmenin geçerliliğine bağlardı. Üretimde
 * gerçek servisin SEI'si aynı desenle yazılır.
 */
@WebService(targetNamespace = "http://sms.framework.zeus.com/")
public interface SmsService {

    /** @return servis tarafından üretilen mesaj kimliği */
    @WebMethod
    String sendSms(@WebParam(name = "to") String to, @WebParam(name = "text") String text);
}
