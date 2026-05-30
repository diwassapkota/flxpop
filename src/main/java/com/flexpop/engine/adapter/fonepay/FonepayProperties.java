package com.flexpop.engine.adapter.fonepay;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Per FP-SPEC-001 + Fonepay's Intent API spec (Web Checkout Intent Flow v1.10).
 *
 * <p>Auth model:
 * <ol>
 *   <li>{@code loginUsername} + {@code loginPassword} → Basic Auth on {@code POST /login}</li>
 *   <li>The login request body is signed (SHA256withRSA) with {@code privateKeyBase64Pkcs8}
 *       and the signature placed in the {@code signature} header</li>
 *   <li>The response carries a Bearer token used on subsequent calls;
 *       those calls are also body-signed</li>
 * </ol>
 *
 * <p>Defaults are deliberately fake placeholders so that an unconfigured environment
 * fails loudly (login 401, signature rejected) rather than silently behaving like
 * the old stub. Override via the {@code FONEPAY_*} env vars in application-dev.yml.
 */
@ConfigurationProperties(prefix = "flexpop.gateways.fonepay")
public record FonepayProperties(
        String terminalId,
        String loginUsername,
        String loginPassword,
        String privateKeyBase64Pkcs8,
        String baseUrl,
        String webhookSecret,
        long pollIntervalMs
) {
    public FonepayProperties {
        if (terminalId == null)            terminalId = "STUB-TERMINAL-ID";
        if (loginUsername == null)         loginUsername = "STUB-USERNAME-CONFIGURE-ME";
        if (loginPassword == null)         loginPassword = "STUB-PASSWORD-CONFIGURE-ME";
        if (privateKeyBase64Pkcs8 == null) privateKeyBase64Pkcs8 = "";
        if (baseUrl == null)               baseUrl = "https://dev-external-gateway-new.fonepay.com/merchantThirdparty";
        if (webhookSecret == null)         webhookSecret = "dev-fonepay-webhook-secret";
        if (pollIntervalMs == 0L)          pollIntervalMs = 5_000L;
    }
}
