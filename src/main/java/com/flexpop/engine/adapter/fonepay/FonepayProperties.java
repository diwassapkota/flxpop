package com.flexpop.engine.adapter.fonepay;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "flexpop.gateways.fonepay")
public record FonepayProperties(
        String merchantId,
        String secret,
        String baseUrl,
        String webhookSecret
) {
    public FonepayProperties {
        if (merchantId == null) merchantId = "STUB-MID";
        if (baseUrl == null)    baseUrl = "https://sandbox.fonepay.com";
        if (webhookSecret == null) webhookSecret = "dev-fonepay-webhook-secret";
    }
}
