package com.zeus.framework.sms;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ZeusSmsPropertiesTest {

    @Test
    void varsayilanTimeoutlarSonsuzDegildir() {
        // CXF'in varsayılanı sonsuz beklemedir; framework bunu ASLA miras almamalı.
        ZeusSmsProperties p = new ZeusSmsProperties();
        assertThat(p.getConnectTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(p.getReceiveTimeout()).isEqualTo(Duration.ofSeconds(15));
    }

    @Test
    void endpointVarsayilanOlarakBostur() {
        // Endpoint verilmedikçe auto-config devreye girmez (koşul bu alana bakar).
        assertThat(new ZeusSmsProperties().getEndpoint()).isNull();
    }
}
