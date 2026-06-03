package com.flxpop.engine.adapter.esewa;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Per eSewa ePay v2 (https://developer.esewa.com.np/pages/Epay).
 *
 * <p>Auth model: there is no login/token. Each payment form POST and each
 * status-check carries an HMAC-SHA256 signature over
 * {@code total_amount=…,transaction_uuid=…,product_code=…} keyed by
 * {@link #secretKey}. eSewa verifies that signature server-side and signs its
 * own success-callback the same way for us to verify.
 *
 * <p>Defaults are the published UAT/sandbox values so an unconfigured dev
 * environment talks to eSewa's test gateway out of the box. Override via the
 * {@code ESEWA_*} env vars / application-dev.yml for a real merchant.
 */
@ConfigurationProperties(prefix = "flxpop.gateways.esewa")
public record EsewaProperties(
        String merchantCode,
        String secretKey,
        String formUrl,
        String statusUrl,
        String engineBaseUrl,
        String returnUrl,
        long pollIntervalMs
) {
    public EsewaProperties {
        if (merchantCode == null)  merchantCode  = "EPAYTEST";
        if (secretKey == null)     secretKey     = "8gBm/:&EnhH.1/q";
        if (formUrl == null)       formUrl       = "https://rc-epay.esewa.com.np/api/epay/main/v2/form";
        if (statusUrl == null)     statusUrl     = "https://rc.esewa.com.np/api/epay/transaction/status/";
        if (engineBaseUrl == null) engineBaseUrl = "http://localhost:8080";
        // Where eSewa's callback sends the shopper's browser back to after we've
        // settled — the merchant checkout page. Blank ⇒ render the engine's own
        // result page instead (no merchant page to return to).
        if (returnUrl == null)     returnUrl     = "http://localhost:5173/demo.html";
        if (pollIntervalMs == 0L)  pollIntervalMs = 5_000L;
    }
}
