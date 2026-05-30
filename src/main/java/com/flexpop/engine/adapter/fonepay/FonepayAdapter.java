package com.flexpop.engine.adapter.fonepay;

import com.flexpop.engine.adapter.GatewayAdapter;
import com.flexpop.engine.domain.Gateway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;

/**
 * Fonepay adapter — PHASE 1 STUB.
 *
 * Returns deterministic-looking fake responses so the end-to-end engine flow
 * works without a sandbox account. When the real sandbox creds land:
 *   1. Inject the merchant ID + secret from FonepayProperties.
 *   2. Replace initiate() body with a signed POST to Fonepay's
 *      /api/merchantRequest endpoint, parse PRN + payment URL from the response.
 *   3. Map their callback shape to GatewayAdapter.StatusResult inside the
 *      inbound webhook handler.
 *
 * Until then, this gives the rest of the engine something real to call.
 */
@Component
public class FonepayAdapter implements GatewayAdapter {

    private static final Duration INTENT_TTL = Duration.ofMinutes(10);
    private static final SecureRandom RNG = new SecureRandom();

    private final FonepayProperties props;

    public FonepayAdapter(FonepayProperties props) {
        this.props = props;
    }

    @Override
    public Gateway gateway() {
        return Gateway.FONEPAY;
    }

    @Override
    public InitiateResult initiate(InitiateRequest req) {
        String gatewayRef = "FP-PRN-" + randomHex(10);
        Instant expiresAt = Instant.now().plus(INTENT_TTL);

        return switch (req.device()) {
            case MOBILE -> new InitiateResult(
                    gatewayRef,
                    buildAppIntentUrl(gatewayRef, req),
                    null,
                    expiresAt);
            case DESKTOP -> new InitiateResult(
                    gatewayRef,
                    null,
                    buildQrPayload(gatewayRef, req),
                    expiresAt);
        };
    }

    @Override
    public StatusResult queryStatus(String gatewayRef) {
        // Sandbox stub: real impl posts to /api/merchantDetailsForThirdParty.
        return new StatusResult(gatewayRef, "PENDING", null, null, null);
    }

    @Override
    public RefundResult refund(RefundRequest req) {
        // Sandbox stub: real impl posts a signed refund request to Fonepay.
        // Real flow: PENDING here, gateway sends refund.settled webhook later.
        String refundGatewayRef = "FP-RF-" + randomHex(10);
        return new RefundResult(refundGatewayRef, "PENDING");
    }

    private String buildAppIntentUrl(String gatewayRef, InitiateRequest req) {
        return "fonepay://pay?prn=%s&amt=%d&ccy=%s&mid=%s".formatted(
                gatewayRef,
                req.amount().minor(),
                req.amount().currency().name(),
                props.merchantId());
    }

    private String buildQrPayload(String gatewayRef, InitiateRequest req) {
        // In real life this is an EMVCo QR string. Stubbed to a recognizable
        // placeholder the widget can render with any QR lib.
        return "00020101021230460016me.fonepay.merchant0108%s0207%s5204000053034245405%d6304STUB".formatted(
                props.merchantId(),
                gatewayRef,
                req.amount().minor());
    }

    private static String randomHex(int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(Integer.toHexString(RNG.nextInt(16)));
        }
        return sb.toString().toUpperCase();
    }
}
