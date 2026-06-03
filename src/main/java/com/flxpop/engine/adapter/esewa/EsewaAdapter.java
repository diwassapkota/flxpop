package com.flxpop.engine.adapter.esewa;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.flxpop.engine.adapter.GatewayAdapter;
import com.flxpop.engine.domain.Currency;
import com.flxpop.engine.domain.Gateway;
import com.flxpop.engine.domain.entity.TransactionEntity;
import com.flxpop.engine.domain.repo.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * eSewa ePay v2 adapter (https://developer.esewa.com.np/pages/Epay).
 *
 * <p>eSewa is a redirect-and-callback gateway, not a QR/poll one like Fonepay:
 * <ul>
 *   <li>{@link #initiate} does NOT call eSewa. It returns an {@code appIntentUrl}
 *       pointing at the engine-hosted checkout page
 *       ({@code /v1/gateways/esewa/checkout/{uuid}}) which renders the signed,
 *       auto-submitting form POST to eSewa. The widget opens that URL in a new
 *       tab (eSewa's hosted page can't be iframed).</li>
 *   <li>Settlement arrives two ways: eSewa redirects the browser to our signed
 *       {@code success_url} callback, and {@link EsewaStatusPoller} reconciles
 *       via {@link #checkStatus} for anything the callback misses.</li>
 *   <li>{@link #refund} stays unsupported — ePay v2 publishes no refund API
 *       (the status check reports FULL/PARTIAL_REFUND but can't initiate one).</li>
 * </ul>
 */
@Component
public class EsewaAdapter implements GatewayAdapter {

    private static final Logger log = LoggerFactory.getLogger(EsewaAdapter.class);

    /** How long the engine-hosted checkout link stays valid. eSewa's own login
     *  session is 5 min, but the user may take a moment to click through. */
    private static final Duration LINK_TTL = Duration.ofMinutes(30);

    static final String SIGNED_FIELD_NAMES = "total_amount,transaction_uuid,product_code";

    private final EsewaProperties props;
    private final EsewaSigner signer;
    private final TransactionRepository txnRepo;
    private final RestClient http;

    public EsewaAdapter(EsewaProperties props, EsewaSigner signer, TransactionRepository txnRepo) {
        this.props = props;
        this.signer = signer;
        this.txnRepo = txnRepo;
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout((int) Duration.ofSeconds(8).toMillis());
        rf.setReadTimeout((int) Duration.ofSeconds(15).toMillis());
        this.http = RestClient.builder().requestFactory(rf).build();
    }

    @Override
    public Gateway gateway() {
        return Gateway.ESEWA;
    }

    @Override
    public InitiateResult initiate(InitiateRequest req) {
        // Our public IDs (FP-NPR-XXXXXX) are alphanumeric + hyphen — exactly
        // eSewa's transaction_uuid charset — so we use them verbatim as the uuid
        // and the gateway_ref. No call to eSewa here: the actual form POST is
        // rendered by EsewaCheckoutController when the shopper opens the link.
        String uuid = req.txnPublicId();
        String checkoutUrl = props.engineBaseUrl() + "/v1/gateways/esewa/checkout/" + uuid;
        return new InitiateResult(uuid, checkoutUrl, null, null, Instant.now().plus(LINK_TTL));
    }

    @Override
    public StatusResult queryStatus(String gatewayRef) {
        TransactionEntity txn = txnRepo.findByGatewayAndGatewayRef(Gateway.ESEWA, gatewayRef).orElse(null);
        if (txn == null) {
            log.warn("eSewa status check: no transaction for gateway_ref={}", gatewayRef);
            return new StatusResult(gatewayRef, "PENDING", null, null, null);
        }
        return checkStatus(gatewayRef, totalAmount(txn.getAmountMinor(), txn.getCurrency()));
    }

    /** eSewa status-check API. {@code total_amount} must match the value the form was signed with. */
    public StatusResult checkStatus(String uuid, String totalAmount) {
        try {
            StatusResponse res = http.get()
                    .uri(props.statusUrl() + "?product_code={pc}&total_amount={ta}&transaction_uuid={uuid}",
                            props.merchantCode(), totalAmount, uuid)
                    .retrieve()
                    .body(StatusResponse.class);
            return mapStatus(uuid, res);
        } catch (RestClientException e) {
            log.warn("eSewa status check for {} failed: {}", uuid, e.getMessage());
            return new StatusResult(uuid, "PENDING", null, null, null);
        }
    }

    private StatusResult mapStatus(String uuid, StatusResponse res) {
        String status = res == null ? null : res.status();
        if (status == null) {
            return new StatusResult(uuid, "PENDING", null, null, null);
        }
        return switch (status.toUpperCase()) {
            case "COMPLETE", "FULL_REFUND", "PARTIAL_REFUND" ->
                    new StatusResult(uuid, "SETTLED", Instant.now(), null, null);
            case "CANCELED" ->
                    new StatusResult(uuid, "FAILED", null, "ESEWA_CANCELED", "Payment canceled at eSewa");
            // PENDING / AMBIGUOUS / NOT_FOUND (session expired) / unknown stay
            // non-terminal. Like the Fonepay path, EXPIRED is decided off the
            // engine's own deadline in the poller — so a freshly-created txn that
            // eSewa doesn't know yet (NOT_FOUND) isn't wrongly failed on first poll.
            default -> new StatusResult(uuid, "PENDING", null, null, null);
        };
    }

    /** Builds the signed eSewa form for the engine-hosted checkout page to auto-submit. */
    public CheckoutForm buildCheckoutForm(String uuid, String totalAmount,
                                          String successUrl, String failureUrl) {
        String signature = signer.sign(requestSignatureMessage(uuid, totalAmount));
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("amount", totalAmount);
        fields.put("tax_amount", "0");
        fields.put("total_amount", totalAmount);
        fields.put("transaction_uuid", uuid);
        fields.put("product_code", props.merchantCode());
        fields.put("product_service_charge", "0");
        fields.put("product_delivery_charge", "0");
        fields.put("success_url", successUrl);
        fields.put("failure_url", failureUrl);
        fields.put("signed_field_names", SIGNED_FIELD_NAMES);
        fields.put("signature", signature);
        return new CheckoutForm(props.formUrl(), fields);
    }

    /** The request signature message — fields in the exact order of {@link #SIGNED_FIELD_NAMES}. */
    private String requestSignatureMessage(String uuid, String totalAmount) {
        return "total_amount=" + totalAmount
                + ",transaction_uuid=" + uuid
                + ",product_code=" + props.merchantCode();
    }

    /** Minor units → eSewa major-unit string, used identically in the form, signature, and status check. */
    public String totalAmount(long minor, Currency currency) {
        return BigDecimal.valueOf(minor, currency.minorUnitScale()).toPlainString();
    }

    public record CheckoutForm(String action, Map<String, String> fields) { }

    record StatusResponse(
            @JsonProperty("product_code") String productCode,
            @JsonProperty("transaction_uuid") String transactionUuid,
            @JsonProperty("total_amount") String totalAmount,
            @JsonProperty("status") String status,
            @JsonProperty("ref_id") String refId
    ) { }
}
