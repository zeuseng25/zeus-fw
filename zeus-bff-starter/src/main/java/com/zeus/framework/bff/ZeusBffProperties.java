package com.zeus.framework.bff;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/** Zeus BFF ayarları ({@code zeus.bff.*}). */
@ConfigurationProperties("zeus.bff")
public class ZeusBffProperties {

    private final SpaFallback spaFallback = new SpaFallback();

    public SpaFallback getSpaFallback() {
        return spaFallback;
    }

    public static class SpaFallback {

        /** SPA fallback açık mı? (kapatmak için false) */
        private boolean enabled = true;

        /**
         * Fallback'in DOKUNMAYACAĞI yol önekleri: bu öneklerle başlayan istekler sıradaki
         * RouterFunction'a (gateway route'ları / controller'lar) bırakılır. Uygulama, kendi
         * gateway route öneklerini buraya EKLEMELİDİR (ör. /api/,/proxy/).
         */
        private List<String> excludePrefixes = new ArrayList<>(List.of("/api/"));

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public List<String> getExcludePrefixes() {
            return excludePrefixes;
        }

        public void setExcludePrefixes(List<String> excludePrefixes) {
            this.excludePrefixes = excludePrefixes;
        }
    }
}
