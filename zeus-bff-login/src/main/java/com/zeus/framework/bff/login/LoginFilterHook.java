package com.zeus.framework.bff.login;

import com.zeus.framework.bff.ZeusBffFilter;

/**
 * Oturum doğrulama filter kancası — İSKELET.
 *
 * <p>Gerçek login implementasyonu geldiğinde, korunan route'ların zincirine eklenecek
 * filter bu arayüz üzerinden sağlanacak (oturum yoksa login akışına yönlendirme,
 * varsa isteğe kimlik bilgisi ekleme). Uygulamalar bugünden bu tipe kodlanabilir.
 */
public interface LoginFilterHook extends ZeusBffFilter {
}
