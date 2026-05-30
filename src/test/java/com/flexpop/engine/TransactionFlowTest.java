package com.flexpop.engine;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionFlowTest extends AbstractIntegrationTest {

    @Autowired
    TestRestTemplate http;

    private HttpHeaders authHeaders(String idempotencyKey) {
        HttpHeaders h = new HttpHeaders();
        h.add("Content-Type", "application/json");
        h.add("Authorization", "Bearer " + DEV_KEY);
        h.add("Sec-CH-UA-Mobile", "?0");
        if (idempotencyKey != null) {
            h.add("Idempotency-Key", idempotencyKey);
        }
        return h;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private String createSession() {
        ResponseEntity<Map> res = http.exchange(
                url("/v1/sessions"), HttpMethod.POST,
                new HttpEntity<String>(
                        "{\"amount\":250000,\"currency\":\"NPR\",\"country\":\"NP\",\"reference\":\"ORDER-X\"}",
                        authHeaders(null)),
                Map.class);
        assertThat(res.getStatusCode().value()).isEqualTo(201);
        return (String) res.getBody().get("session_id");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void createReplayConflict_fullIdempotencyContract() {
        String sessionId = createSession();
        String key = "demo-" + UUID.randomUUID();

        // 1) First call → 201, status ROUTED, two events.
        ResponseEntity<Map> first = http.exchange(
                url("/v1/transactions"), HttpMethod.POST,
                new HttpEntity<String>(
                        "{\"session_id\":\"" + sessionId + "\",\"gateway\":\"FONEPAY\"}",
                        authHeaders(key)),
                Map.class);

        assertThat(first.getStatusCode().value()).isEqualTo(201);
        Map firstBody = first.getBody();
        String txnId = (String) firstBody.get("txn_id");
        assertThat(txnId).startsWith("FP-NPR-");
        assertThat(firstBody).containsEntry("status", "ROUTED");
        assertThat(firstBody).containsEntry("gateway", "FONEPAY");
        assertThat(firstBody.get("qr_payload")).isNotNull();    // DESKTOP path
        assertThat(firstBody.get("app_intent_url")).isNull();
        List events = (List) firstBody.get("events");
        assertThat(events).hasSize(2);

        // 2) Replay with same key + same body → 200, Idempotency-Replayed: true,
        //    identical txn_id, no new events.
        ResponseEntity<Map> replay = http.exchange(
                url("/v1/transactions"), HttpMethod.POST,
                new HttpEntity<String>(
                        "{\"session_id\":\"" + sessionId + "\",\"gateway\":\"FONEPAY\"}",
                        authHeaders(key)),
                Map.class);

        assertThat(replay.getStatusCode().value()).isEqualTo(200);
        assertThat(replay.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("true");
        assertThat(replay.getBody()).containsEntry("txn_id", txnId);

        // 3) Same key, different body → 409.
        ResponseEntity<Map> conflict = http.exchange(
                url("/v1/transactions"), HttpMethod.POST,
                new HttpEntity<String>(
                        "{\"session_id\":\"" + sessionId + "\",\"gateway\":\"ESEWA\"}",
                        authHeaders(key)),
                Map.class);

        assertThat(conflict.getStatusCode().value()).isEqualTo(409);
        Map<String, Object> err = (Map<String, Object>) conflict.getBody().get("error");
        assertThat(err).containsEntry("type", "idempotency_conflict");
    }

    @Test
    @SuppressWarnings("rawtypes")
    void postTransactionWithoutIdempotencyKeyIs400() {
        String sessionId = createSession();

        // No Idempotency-Key.
        ResponseEntity<Map> res = http.exchange(
                url("/v1/transactions"), HttpMethod.POST,
                new HttpEntity<String>(
                        "{\"session_id\":\"" + sessionId + "\",\"gateway\":\"FONEPAY\"}",
                        authHeaders(null)),
                Map.class);

        assertThat(res.getStatusCode().value()).isEqualTo(400);
        Map<String, Object> err = (Map<String, Object>) res.getBody().get("error");
        assertThat(err).containsEntry("type", "bad_request");
    }

    @Test
    @SuppressWarnings("rawtypes")
    void unknownSessionIs404() {
        ResponseEntity<Map> res = http.exchange(
                url("/v1/transactions"), HttpMethod.POST,
                new HttpEntity<String>(
                        "{\"session_id\":\"SES-NOPE99\",\"gateway\":\"FONEPAY\"}",
                        authHeaders("k-" + UUID.randomUUID())),
                Map.class);
        assertThat(res.getStatusCode().value()).isEqualTo(404);
    }
}
