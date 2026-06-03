package com.flxpop.engine;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the contract: a publishable key ({@code pk_*}) is allowed to do only
 * what a browser-side widget needs — create a transaction for an existing
 * session, and read that transaction's status. Everything else returns 403.
 *
 * <p>A secret key ({@code sk_*}) keeps full access — regression-checked here
 * so a misplaced annotation can't silently restrict the server-side surface.
 */
class PkScopeTest extends AbstractIntegrationTest {

    @Autowired
    TestRestTemplate http;

    private HttpHeaders headers(String bearerKey, String idempotencyKey) {
        HttpHeaders h = new HttpHeaders();
        h.add("Content-Type", "application/json");
        h.add("Authorization", "Bearer " + bearerKey);
        h.add("Sec-CH-UA-Mobile", "?0");
        if (idempotencyKey != null) h.add("Idempotency-Key", idempotencyKey);
        return h;
    }

    /** Build a session + txn using sk; we then probe routes using pk. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private String[] bootstrapSessionAndTxn() {
        ResponseEntity<Map> ses = http.exchange(
                url("/v1/sessions"), HttpMethod.POST,
                new HttpEntity<String>(
                        "{\"amount\":1000,\"currency\":\"NPR\",\"country\":\"NP\"}",
                        headers(DEV_KEY, null)),
                Map.class);
        assertThat(ses.getStatusCode().value()).isEqualTo(201);
        String sessionId = (String) ses.getBody().get("session_id");

        ResponseEntity<Map> txn = http.exchange(
                url("/v1/transactions"), HttpMethod.POST,
                new HttpEntity<String>(
                        "{\"session_id\":\"" + sessionId + "\",\"gateway\":\"FONEPAY\"}",
                        headers(DEV_KEY, "pk-bootstrap-" + UUID.randomUUID())),
                Map.class);
        assertThat(txn.getStatusCode().value()).isEqualTo(201);
        return new String[] { sessionId, (String) txn.getBody().get("txn_id") };
    }

    // ─── publishable key: what it CAN do ──────────────────────────────────

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void pkCanCreateTransactionAgainstExistingSession() {
        String sessionId = bootstrapSessionAndTxn()[0];

        ResponseEntity<Map> res = http.exchange(
                url("/v1/transactions"), HttpMethod.POST,
                new HttpEntity<String>(
                        "{\"session_id\":\"" + sessionId + "\",\"gateway\":\"FONEPAY\"}",
                        headers(DEV_PK, "pk-test-" + UUID.randomUUID())),
                Map.class);

        assertThat(res.getStatusCode().value()).isEqualTo(201);
        assertThat((String) res.getBody().get("txn_id")).startsWith("FP-NPR-");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void pkCanGetTransaction() {
        String txnId = bootstrapSessionAndTxn()[1];

        ResponseEntity<Map> res = http.exchange(
                url("/v1/transactions/" + txnId), HttpMethod.GET,
                new HttpEntity<String>(headers(DEV_PK, null)), Map.class);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody()).containsEntry("txn_id", txnId);
    }

    // ─── publishable key: what it CANNOT do ───────────────────────────────

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void pkCannotCreateSession() {
        ResponseEntity<Map> res = http.exchange(
                url("/v1/sessions"), HttpMethod.POST,
                new HttpEntity<String>(
                        "{\"amount\":1000,\"currency\":\"NPR\",\"country\":\"NP\"}",
                        headers(DEV_PK, null)),
                Map.class);
        assertThat(res.getStatusCode().value()).isEqualTo(403);
        Map<String, Object> err = (Map<String, Object>) res.getBody().get("error");
        assertThat(err).containsEntry("type", "forbidden");
        assertThat((String) err.get("message")).contains("publishable");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void pkCannotCreateRefund() {
        // We need a SETTLED txn to even reach the refund route's body validation
        // — but the secret-key guard fires BEFORE the controller runs, so a
        // plain ROUTED txn is enough to provoke the 403 we want to assert.
        String txnId = bootstrapSessionAndTxn()[1];

        ResponseEntity<Map> res = http.exchange(
                url("/v1/transactions/" + txnId + "/refunds"), HttpMethod.POST,
                new HttpEntity<String>(
                        "{\"amount\":500,\"reason\":\"shouldn't reach controller\"}",
                        headers(DEV_PK, "pk-rf-" + UUID.randomUUID())),
                Map.class);
        assertThat(res.getStatusCode().value()).isEqualTo(403);
        Map<String, Object> err = (Map<String, Object>) res.getBody().get("error");
        assertThat(err).containsEntry("type", "forbidden");
    }

    // ─── regression: secret key keeps full access ─────────────────────────

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void skStillWorksOnEveryRestrictedRoute() {
        // sk on /v1/sessions
        ResponseEntity<Map> ses = http.exchange(
                url("/v1/sessions"), HttpMethod.POST,
                new HttpEntity<String>(
                        "{\"amount\":1000,\"currency\":\"NPR\",\"country\":\"NP\"}",
                        headers(DEV_KEY, null)),
                Map.class);
        assertThat(ses.getStatusCode().value()).isEqualTo(201);

        // sk on /v1/transactions
        ResponseEntity<Map> txn = http.exchange(
                url("/v1/transactions"), HttpMethod.POST,
                new HttpEntity<String>(
                        "{\"session_id\":\"" + ses.getBody().get("session_id") + "\",\"gateway\":\"FONEPAY\"}",
                        headers(DEV_KEY, "sk-regress-" + UUID.randomUUID())),
                Map.class);
        assertThat(txn.getStatusCode().value()).isEqualTo(201);

        // sk on /v1/transactions/{id}/refunds — txn is ROUTED so we expect
        // 400 (only SETTLED can be refunded), NOT 403 — proving the secret-key
        // guard let us reach the controller.
        ResponseEntity<Map> rf = http.exchange(
                url("/v1/transactions/" + txn.getBody().get("txn_id") + "/refunds"),
                HttpMethod.POST,
                new HttpEntity<String>(
                        "{\"amount\":500,\"reason\":\"sk should reach controller\"}",
                        headers(DEV_KEY, "sk-rf-regress-" + UUID.randomUUID())),
                Map.class);
        assertThat(rf.getStatusCode().value()).isEqualTo(400);
        Map<String, Object> err = (Map<String, Object>) rf.getBody().get("error");
        assertThat(err).containsEntry("type", "bad_request");
    }
}
