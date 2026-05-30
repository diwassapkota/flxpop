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

class InboundWebhookTest extends AbstractIntegrationTest {

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

    @SuppressWarnings({"rawtypes", "unchecked"})
    private String[] createSessionAndTxn() {
        ResponseEntity<Map> ses = http.exchange(
                url("/v1/sessions"), HttpMethod.POST,
                new HttpEntity<String>("{\"amount\":1000,\"currency\":\"NPR\",\"country\":\"NP\"}", authHeaders(null)),
                Map.class);
        String sessionId = (String) ses.getBody().get("session_id");

        ResponseEntity<Map> txn = http.exchange(
                url("/v1/transactions"), HttpMethod.POST,
                new HttpEntity<String>("{\"session_id\":\"" + sessionId + "\",\"gateway\":\"FONEPAY\"}",
                        authHeaders("k-" + UUID.randomUUID())),
                Map.class);
        String txnId = (String) txn.getBody().get("txn_id");
        String gatewayRef = (String) txn.getBody().get("gateway_ref");
        return new String[] { txnId, gatewayRef };
    }

    private static String hmacSha256(String body, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void validHmacSettlesTxnAndAppendsGatewayEvent() {
        String[] ids = createSessionAndTxn();
        String txnId = ids[0];
        String gatewayRef = ids[1];

        String body = "{\"event_id\":\"fp-evt-" + UUID.randomUUID() + "\",\"type\":\"payment.settled\","
                + "\"gateway_ref\":\"" + gatewayRef + "\"}";

        HttpHeaders h = new HttpHeaders();
        h.add("Content-Type", "application/json");
        h.add("FP-Signature", hmacSha256(body, DEV_FONEPAY_WEBHOOK_SECRET));

        ResponseEntity<Map> webhookRes = http.exchange(
                url("/v1/webhooks/gateways/FONEPAY"), HttpMethod.POST,
                new HttpEntity<String>(body, h), Map.class);
        assertThat(webhookRes.getStatusCode().value()).isEqualTo(200);
        assertThat(webhookRes.getBody()).containsEntry("status", "processed");

        // Verify state via GET /v1/transactions/{id}
        ResponseEntity<Map> get = http.exchange(
                url("/v1/transactions/" + txnId), HttpMethod.GET,
                new HttpEntity<String>(authHeaders(null)), Map.class);
        Map gotBody = get.getBody();
        assertThat(gotBody).containsEntry("status", "SETTLED");
        assertThat(gotBody.get("settled_at")).isNotNull();

        List<Map<String, Object>> events = (List<Map<String, Object>>) gotBody.get("events");
        assertThat(events).hasSize(3);
        assertThat(events.get(2)).containsEntry("type", "payment.settled");
        assertThat(events.get(2)).containsEntry("source", "GATEWAY");
    }

    @Test
    @SuppressWarnings("rawtypes")
    void badSignatureIs401AndDoesNotTransitionTxn() {
        String[] ids = createSessionAndTxn();
        String txnId = ids[0];
        String gatewayRef = ids[1];

        String body = "{\"event_id\":\"fp-evt-bad\",\"type\":\"payment.settled\","
                + "\"gateway_ref\":\"" + gatewayRef + "\"}";
        HttpHeaders h = new HttpHeaders();
        h.add("Content-Type", "application/json");
        h.add("FP-Signature", "deadbeef");

        ResponseEntity<Map> res = http.exchange(
                url("/v1/webhooks/gateways/FONEPAY"), HttpMethod.POST,
                new HttpEntity<String>(body, h), Map.class);
        assertThat(res.getStatusCode().value()).isEqualTo(401);

        // Txn must still be ROUTED, not SETTLED.
        ResponseEntity<Map> get = http.exchange(
                url("/v1/transactions/" + txnId), HttpMethod.GET,
                new HttpEntity<String>(authHeaders(null)), Map.class);
        assertThat(get.getBody()).containsEntry("status", "ROUTED");
    }

    @Test
    @SuppressWarnings("rawtypes")
    void duplicateEventIdIsAckedAndAppendsNoExtraEvent() {
        String[] ids = createSessionAndTxn();
        String txnId = ids[0];
        String gatewayRef = ids[1];

        String eventId = "fp-evt-dup-" + UUID.randomUUID();
        String body = "{\"event_id\":\"" + eventId + "\",\"type\":\"payment.settled\","
                + "\"gateway_ref\":\"" + gatewayRef + "\"}";
        HttpHeaders h = new HttpHeaders();
        h.add("Content-Type", "application/json");
        h.add("FP-Signature", hmacSha256(body, DEV_FONEPAY_WEBHOOK_SECRET));
        HttpEntity<String> req = new HttpEntity<String>(body, h);

        ResponseEntity<Map> first  = http.exchange(url("/v1/webhooks/gateways/FONEPAY"), HttpMethod.POST, req, Map.class);
        ResponseEntity<Map> second = http.exchange(url("/v1/webhooks/gateways/FONEPAY"), HttpMethod.POST, req, Map.class);

        assertThat(first.getStatusCode().value()).isEqualTo(200);
        assertThat(first.getBody()).containsEntry("status", "processed");
        assertThat(second.getStatusCode().value()).isEqualTo(200);
        assertThat(second.getBody()).containsEntry("status", "duplicate");

        ResponseEntity<Map> get = http.exchange(
                url("/v1/transactions/" + txnId), HttpMethod.GET,
                new HttpEntity<String>(authHeaders(null)), Map.class);
        @SuppressWarnings("unchecked")
        List<Object> events = (List<Object>) get.getBody().get("events");
        // created + routed + 1× payment.settled — replay didn't double-append.
        assertThat(events).hasSize(3);
    }
}
