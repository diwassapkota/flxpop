package com.flexpop.engine;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RefundFlowTest extends AbstractIntegrationTest {

    @Autowired
    TestRestTemplate http;

    private HttpHeaders authHeaders(String idempotencyKey) {
        HttpHeaders h = new HttpHeaders();
        h.add("Content-Type", "application/json");
        h.add("Authorization", "Bearer " + DEV_KEY);
        h.add("Sec-CH-UA-Mobile", "?0");
        if (idempotencyKey != null) h.add("Idempotency-Key", idempotencyKey);
        return h;
    }

    private static String hmacSha256(String body, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    /** Create session + txn + settle via webhook. Returns [txnId, gatewayRef]. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private String[] createSettledTxn(long amount) {
        ResponseEntity<Map> ses = http.exchange(
                url("/v1/sessions"), HttpMethod.POST,
                new HttpEntity<String>(
                        "{\"amount\":" + amount + ",\"currency\":\"NPR\",\"country\":\"NP\"}",
                        authHeaders(null)),
                Map.class);
        String sessionId = (String) ses.getBody().get("session_id");

        ResponseEntity<Map> txn = http.exchange(
                url("/v1/transactions"), HttpMethod.POST,
                new HttpEntity<String>(
                        "{\"session_id\":\"" + sessionId + "\",\"gateway\":\"FONEPAY\"}",
                        authHeaders("k-txn-" + UUID.randomUUID())),
                Map.class);
        String txnId = (String) txn.getBody().get("txn_id");
        String gatewayRef = (String) txn.getBody().get("gateway_ref");

        // Settle via inbound webhook
        String body = "{\"event_id\":\"settle-" + UUID.randomUUID() + "\",\"type\":\"payment.settled\","
                + "\"gateway_ref\":\"" + gatewayRef + "\"}";
        HttpHeaders h = new HttpHeaders();
        h.add("Content-Type", "application/json");
        h.add("FP-Signature", hmacSha256(body, DEV_FONEPAY_WEBHOOK_SECRET));
        http.exchange(url("/v1/webhooks/gateways/FONEPAY"), HttpMethod.POST,
                new HttpEntity<String>(body, h), Map.class);

        return new String[] { txnId, gatewayRef };
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ResponseEntity<Map> postRefund(String txnId, long amount, String key) {
        return http.exchange(
                url("/v1/transactions/" + txnId + "/refunds"), HttpMethod.POST,
                new HttpEntity<String>(
                        "{\"amount\":" + amount + ",\"reason\":\"test refund\"}",
                        authHeaders(key)),
                Map.class);
    }

    private void simulateRefundSettled(String refundGatewayRef) {
        String body = "{\"event_id\":\"rfevt-" + UUID.randomUUID() + "\","
                + "\"type\":\"refund.settled\","
                + "\"gateway_ref\":\"" + refundGatewayRef + "\"}";
        HttpHeaders h = new HttpHeaders();
        h.add("Content-Type", "application/json");
        h.add("FP-Signature", hmacSha256(body, DEV_FONEPAY_WEBHOOK_SECRET));
        @SuppressWarnings({"rawtypes", "unchecked"})
        ResponseEntity<Map> res = http.exchange(
                url("/v1/webhooks/gateways/FONEPAY"), HttpMethod.POST,
                new HttpEntity<String>(body, h), Map.class);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
    }

    // -----------------------------------------------------------------------

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void cannotRefundANonSettledTxn() {
        // Build a txn but DON'T settle it — it stays ROUTED.
        ResponseEntity<Map> ses = http.exchange(
                url("/v1/sessions"), HttpMethod.POST,
                new HttpEntity<String>(
                        "{\"amount\":10000,\"currency\":\"NPR\",\"country\":\"NP\"}",
                        authHeaders(null)),
                Map.class);
        String sessionId = (String) ses.getBody().get("session_id");

        ResponseEntity<Map> txn = http.exchange(
                url("/v1/transactions"), HttpMethod.POST,
                new HttpEntity<String>(
                        "{\"session_id\":\"" + sessionId + "\",\"gateway\":\"FONEPAY\"}",
                        authHeaders("k-" + UUID.randomUUID())),
                Map.class);
        String txnId = (String) txn.getBody().get("txn_id");

        ResponseEntity<Map> res = postRefund(txnId, 1000, "k-rf-" + UUID.randomUUID());
        assertThat(res.getStatusCode().value()).isEqualTo(400);
        Map<String, Object> err = (Map<String, Object>) res.getBody().get("error");
        assertThat(err).containsEntry("type", "bad_request");
        assertThat((String) err.get("message")).contains("ROUTED");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void postRefund_returns202_AndAdapterStubGivesAGatewayRef() {
        String[] ids = createSettledTxn(50000);
        String txnId = ids[0];

        ResponseEntity<Map> res = postRefund(txnId, 20000, "k-rf-" + UUID.randomUUID());
        assertThat(res.getStatusCode().value()).isEqualTo(202);

        Map body = res.getBody();
        assertThat((String) body.get("refund_id")).startsWith("RF-NPR-");
        assertThat(body).containsEntry("txn_id", txnId);
        assertThat(((Number) body.get("amount")).longValue()).isEqualTo(20000L);
        assertThat(body).containsEntry("status", "PENDING");
        assertThat((String) body.get("gateway_ref")).startsWith("FP-RF-");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void fullRefundSettles_TxnBecomesREFUNDED() {
        String[] ids = createSettledTxn(30000);
        String txnId = ids[0];

        ResponseEntity<Map> res = postRefund(txnId, 30000, "k-rf-" + UUID.randomUUID());
        String refundGwRef = (String) res.getBody().get("gateway_ref");
        simulateRefundSettled(refundGwRef);

        // Verify
        ResponseEntity<Map> get = http.exchange(
                url("/v1/transactions/" + txnId), HttpMethod.GET,
                new HttpEntity<String>(authHeaders(null)), Map.class);
        Map t = get.getBody();
        assertThat(t).containsEntry("status", "REFUNDED");
        assertThat(((Number) t.get("refunded_amount")).longValue()).isEqualTo(30000L);
        List<Map<String, Object>> refunds = (List<Map<String, Object>>) t.get("refunds");
        assertThat(refunds).hasSize(1);
        assertThat(refunds.get(0)).containsEntry("status", "SETTLED");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void partialRefundLeavesTxnSETTLED_andTwoPartialsTotallingFlipToREFUNDED() {
        String[] ids = createSettledTxn(40000);
        String txnId = ids[0];

        // First partial — 15000
        ResponseEntity<Map> r1 = postRefund(txnId, 15000, "k-rf-" + UUID.randomUUID());
        simulateRefundSettled((String) r1.getBody().get("gateway_ref"));

        ResponseEntity<Map> mid = http.exchange(
                url("/v1/transactions/" + txnId), HttpMethod.GET,
                new HttpEntity<String>(authHeaders(null)), Map.class);
        assertThat(mid.getBody()).containsEntry("status", "SETTLED");
        assertThat(((Number) mid.getBody().get("refunded_amount")).longValue()).isEqualTo(15000L);

        // Second partial — 25000, totalling 40000 → REFUNDED
        ResponseEntity<Map> r2 = postRefund(txnId, 25000, "k-rf-" + UUID.randomUUID());
        simulateRefundSettled((String) r2.getBody().get("gateway_ref"));

        ResponseEntity<Map> end = http.exchange(
                url("/v1/transactions/" + txnId), HttpMethod.GET,
                new HttpEntity<String>(authHeaders(null)), Map.class);
        assertThat(end.getBody()).containsEntry("status", "REFUNDED");
        assertThat(((Number) end.getBody().get("refunded_amount")).longValue()).isEqualTo(40000L);
        List<Map<String, Object>> refunds = (List<Map<String, Object>>) end.getBody().get("refunds");
        assertThat(refunds).hasSize(2);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void refundExceedingRemainingIs400() {
        String[] ids = createSettledTxn(10000);
        String txnId = ids[0];

        // Already-refunded 6000
        ResponseEntity<Map> ok = postRefund(txnId, 6000, "k-rf-" + UUID.randomUUID());
        simulateRefundSettled((String) ok.getBody().get("gateway_ref"));

        // Try to refund 5000 more — only 4000 remaining → 400
        ResponseEntity<Map> over = postRefund(txnId, 5000, "k-rf-" + UUID.randomUUID());
        assertThat(over.getStatusCode().value()).isEqualTo(400);
        Map<String, Object> err = (Map<String, Object>) over.getBody().get("error");
        assertThat((String) err.get("message")).contains("exceeds remaining");
    }

    @Test
    @SuppressWarnings("rawtypes")
    void missingIdempotencyKeyIs400() {
        String[] ids = createSettledTxn(5000);
        ResponseEntity<Map> res = postRefund(ids[0], 1000, null);
        assertThat(res.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void replayReturnsCachedBody() {
        String[] ids = createSettledTxn(20000);
        String txnId = ids[0];
        String key = "k-rf-" + UUID.randomUUID();

        ResponseEntity<Map> first = postRefund(txnId, 5000, key);
        assertThat(first.getStatusCode().value()).isEqualTo(202);
        String firstRefundId = (String) first.getBody().get("refund_id");

        ResponseEntity<Map> replay = postRefund(txnId, 5000, key);
        assertThat(replay.getStatusCode().value()).isEqualTo(200);
        assertThat(replay.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("true");
        assertThat(replay.getBody()).containsEntry("refund_id", firstRefundId);
    }
}
