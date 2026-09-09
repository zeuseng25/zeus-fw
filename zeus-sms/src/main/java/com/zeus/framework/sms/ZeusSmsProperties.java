package com.zeus.framework.sms;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SMS istemcisi ayarları ({@code zeus.sms.*}).
 *
 * <p>{@code endpoint} verilmedikçe auto-configuration devreye girmez: {@code zeus-sms} jar'ını
 * gören ama SMS kullanmayan uygulama hiçbir bean kurmaz ("module geniştir, uygulama dardır").
 */
@ConfigurationProperties(prefix = "zeus.sms")
public class ZeusSmsProperties {

    /** SOAP endpoint URL'i. Boşsa SMS istemcisi hiç kurulmaz. */
    private String endpoint;

    /** Bağlantı zaman aşımı. CXF'in varsayılanı SONSUZ beklemedir; framework bunu miras almaz. */
    private Duration connectTimeout = Duration.ofSeconds(5);

    /** Yanıt bekleme zaman aşımı. Aynı gerekçe. */
    private Duration receiveTimeout = Duration.ofSeconds(15);

    private String username;
    private String password;

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
    public Duration getReceiveTimeout() { return receiveTimeout; }
    public void setReceiveTimeout(Duration receiveTimeout) { this.receiveTimeout = receiveTimeout; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
}
