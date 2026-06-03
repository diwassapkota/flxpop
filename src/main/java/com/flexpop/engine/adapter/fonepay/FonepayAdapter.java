package com.flexpop.engine.adapter.fonepay;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.flexpop.engine.adapter.GatewayAdapter;
import com.flexpop.engine.domain.Gateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * Real Fonepay Intent API adapter (replaces the FP-SPEC-001 PHASE 1 stub).
 *
 * <p>Behavior:
 * <ul>
 *   <li>{@link #initiate} → {@code POST /api/merchant/third-party/v2/generate-intent-qr}
 *       with terminal/reference/amount. Persists the returned {@code qrString} as
 *       {@code qrPayload} and uses the returned {@code prn} as {@code gatewayRef}.
 *       The same {@code qrString} drives BOTH device modes: desktop renders it as
 *       a QR; mobile is rendered as a bank picker whose per-bank deep-links are
 *       assembled at response-build time from the {@link FonepayBankCatalog}.</li>
 *   <li>{@link #queryStatus} → {@code POST /thirdPartyDynamicQrGetStatus}. Mapped
 *       to a {@link StatusResult} the {@code FonepayStatusPoller} consumes.</li>
 *   <li>{@link #refund} stays stub — the Intent API docs do not describe a
 *       refund endpoint. Real refunds settle off-band today.</li>
 * </ul>
 */
@Component
public class FonepayAdapter implements GatewayAdapter {

    private static final Logger log = LoggerFactory.getLogger(FonepayAdapter.class);

    private static final Duration INTENT_TTL = Duration.ofMinutes(10);
    private static final String INTENT_QR_PATH = "/api/merchant/third-party/v2/generate-intent-qr";
    private static final String STATUS_PATH    = "/api/merchant/third-party/v2/thirdPartyDynamicQrGetStatus";

    private final RestClient http;
    private final FonepayProperties props;
    private final FonepayTokenCache tokenCache;
    private final SecureRandom rng = new SecureRandom();

    public FonepayAdapter(@Qualifier("fonepayAuthedClient") RestClient http,
                          FonepayProperties props,
                          FonepayTokenCache tokenCache) {
        this.http = http;
        this.props = props;
        this.tokenCache = tokenCache;
    }

    /**
     * Runs an authed Fonepay call, recovering from a stale Bearer token.
     *
     * <p>The cached token is good for ~1h, but Fonepay's sandbox effectively
     * allows a single active session per merchant: any other login with the
     * same credentials (a smoke test, Postman, a second engine) silently
     * revokes our token, and the cache has no way to know until a call comes
     * back {@code 401}. When that happens we drop the token, force a fresh
     * login, and retry exactly once. A second {@code 401} is a genuine auth
     * failure (bad creds / bad signature) and is allowed to propagate.
     */
    private <T> T withFreshAuthOn401(Supplier<T> call) {
        try {
            return call.get();
        } catch (HttpClientErrorException.Unauthorized e) {
            log.info("Fonepay returned 401 — refreshing the Bearer token and retrying once");
            tokenCache.invalidate();
            return call.get();
        }
    }

    @Override
    public Gateway gateway() {
        return Gateway.FONEPAY;
    }

    @Override
    public InitiateResult initiate(InitiateRequest req) {
        // referenceLabel must be alphanumeric ≤30 chars. Our public IDs (FP-NPR-XXXXXX)
        // contain hyphens; strip them defensively.
        String referenceLabel = req.txnPublicId().replaceAll("[^A-Za-z0-9]", "");
        // Fonepay expects amount in major units as a decimal (e.g. "30500.00"),
        // not minor units. We store minor internally and convert here.
        BigDecimal amount = BigDecimal.valueOf(
                req.amount().minor(), req.amount().currency().minorUnitScale());

        IntentQrRequest body = new IntentQrRequest(
                amount,
                referenceLabel,
                props.terminalId(),
                "QR",
                referenceLabel,
                "INTENT_QR");

        try {
            IntentQrResponse res = withFreshAuthOn401(() -> http.post()
                    .uri(INTENT_QR_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(IntentQrResponse.class));
            if (res == null || res.qrString() == null) {
                throw new IllegalStateException("Fonepay /generate-intent-qr returned no qrString");
            }
            String prn = res.prn() != null ? res.prn() : referenceLabel;
            return new InitiateResult(
                    prn,
                    null,             // appIntentUrl: derived at response-render time per bank
                    res.qrString(),   // qrPayload: same for mobile AND desktop
                    res.websocketId(),// real-time payment-notification socket (wss://ws.fonepay.com/…)
                    Instant.now().plus(INTENT_TTL));
        } catch (RestClientException e) {
            throw new IllegalStateException(
                    "Fonepay /generate-intent-qr failed for txn " + req.txnPublicId() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public StatusResult queryStatus(String gatewayRef) {
        StatusQueryRequest body = new StatusQueryRequest(props.terminalId(), gatewayRef);
        try {
            StatusQueryResponse res = withFreshAuthOn401(() -> http.post()
                    .uri(STATUS_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(StatusQueryResponse.class));
            return mapStatus(gatewayRef, res);
        } catch (RestClientException e) {
            // Don't escalate — the poller treats a transient failure as "still PENDING"
            // and will retry on the next tick. A persistent failure becomes visible
            // through the txn's last_polled_error log line, not by failing the request.
            log.warn("Fonepay status query for {} failed: {}", gatewayRef, e.getMessage());
            return new StatusResult(gatewayRef, "PENDING", null, null, null);
        }
    }

    private StatusResult mapStatus(String gatewayRef, StatusQueryResponse res) {
        if (res == null || res.paymentStatus() == null) {
            return new StatusResult(gatewayRef, "PENDING", null, null, null);
        }
        return switch (res.paymentStatus().toLowerCase()) {
            case "success" -> new StatusResult(gatewayRef, "SETTLED", Instant.now(), null, null);
            case "failed"  -> new StatusResult(gatewayRef, "FAILED", null, "GATEWAY_FAILED", res.paymentMessage());
            // Everything else — notably "timeout"/"Data not found." which Fonepay
            // returns for an unscanned QR (verified live, even seconds after the QR
            // is created) — is PENDING. We deliberately do NOT treat "timeout" as
            // expired here: that would expire fresh, unpaid txns on the first poll.
            // Expiry is driven by the txn's own deadline in FonepayStatusPoller.
            default        -> new StatusResult(gatewayRef, "PENDING", null, null, null);
        };
    }

    @Override
    public RefundResult refund(RefundRequest req) {
        // Fonepay Intent API docs (v1.10) do not publish a refund endpoint.
        // Returning a synthetic PENDING refund_ref keeps the engine's refund
        // contract honest; merchants settle Fonepay refunds out-of-band until
        // a vendor refund API exists.
        return new RefundResult("FP-RF-" + randomHex(10), "PENDING");
    }

    private String randomHex(int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(Integer.toHexString(rng.nextInt(16)));
        return sb.toString().toUpperCase();
    }

    record IntentQrRequest(
            @JsonProperty("amount") BigDecimal amount,
            @JsonProperty("billId") String billId,
            @JsonProperty("terminalId") String terminalId,
            @JsonProperty("paymentMode") String paymentMode,
            @JsonProperty("referenceLabel") String referenceLabel,
            @JsonProperty("qrType") String qrType
    ) { }

    record IntentQrResponse(
            @JsonProperty("qrString") String qrString,
            @JsonProperty("prn") String prn,
            @JsonProperty("terminalId") String terminalId,
            @JsonProperty("status") String status,
            @JsonProperty("qrMessage") String qrMessage,
            @JsonProperty("websocketId") String websocketId
    ) { }

    record StatusQueryRequest(
            @JsonProperty("terminalId") String terminalId,
            @JsonProperty("referenceLabel") String referenceLabel
    ) { }

    record StatusQueryResponse(
            @JsonProperty("paymentStatus") String paymentStatus,
            @JsonProperty("paymentMessage") String paymentMessage,
            @JsonProperty("fonepayTraceId") Long fonepayTraceId
    ) { }
}
