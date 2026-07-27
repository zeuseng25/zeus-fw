package com.zeus.framework.bff.login;

/**
 * Oturum yaşam döngüsü kancası — İSKELET.
 *
 * <p>Login/logout ve token yenileme anlarında çağrılacak uzantı noktası; gerçek
 * implementasyon kurum kimlik sağlayıcısı entegrasyonuyla birlikte gelecek.
 */
public interface SessionHook {

    /** Oturum açıldığında çağrılır (kullanıcı kimliği: uygulamanın belirlediği anahtar). */
    default void onLogin(String principal) {
    }

    /** Oturum kapandığında/expire olduğunda çağrılır. */
    default void onLogout(String principal) {
    }
}
