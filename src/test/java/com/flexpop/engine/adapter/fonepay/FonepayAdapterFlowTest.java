package com.flexpop.engine.adapter.fonepay;

import com.flexpop.engine.AbstractIntegrationTest;
import com.flexpop.engine.domain.entity.TransactionEntity;
import com.flexpop.engine.domain.repo.TransactionRepository;
import com.flexpop.engine.webhook.FonepayStatusPoller;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

class FonepayAdapterFlowTest extends AbstractIntegrationTest {

    @Autowired
    TestRestTemplate http;

    @Autowired
    FonepayStatusPoller poller;

    @Autowired
    FonepayBankCatalog bankCatalog;

    @Autowired
    FonepayTokenCache tokenCache;

    @Autowired
    TransactionRepository txnRepo;

    @BeforeEach
    void freshStubsPerTest() {
        resetFonepayStubs();
        // Shared Spring context across test classes means token cache + bank
        // catalog persist between tests — force a fresh login + bank list so
        // every test exercises the full request chain against this test's stubs.
        tokenCache.invalidate();
        bankCatalog.refresh();
    }

    private HttpHeaders authHeaders(String idempotencyKey, boolean mobile) {
        HttpHeaders h = new HttpHeaders();
        h.add("Content-Type", "application/json");
        h.add("Authorization", "Bearer " + DEV_KEY);
        h.add("Sec-CH-UA-Mobile", mobile ? "?1" : "?0");
        if (idempotencyKey != null) h.add("Idempotency-Key", idempotencyKey);
        return h;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private String[] createMobileSessionAndTxn() {
        ResponseEntity<Map> ses = http.exchange(
                url("/v1/sessions"), HttpMethod.POST,
                new HttpEntity<String>(
                        "{\"amount\":3050000,\"currency\":\"NPR\",\"country\":\"NP\",\"reference\":\"FP-IT-1\"}",
                        authHeaders(null, true)),
                Map.class);
        assertThat(ses.getStatusCode().value()).isEqualTo(201);
        assertThat(ses.getBody()).containsEntry("device", "MOBILE");
        String sessionId = (String) ses.getBody().get("session_id");

        ResponseEntity<Map> txn = http.exchange(
                url("/v1/transactions"), HttpMethod.POST,
                new HttpEntity<String>(
                        "{\"session_id\":\"" + sessionId + "\",\"gateway\":\"FONEPAY\"}",
                        authHeaders("fp-it-" + UUID.randomUUID(), true)),
                Map.class);
        assertThat(txn.getStatusCode().value()).isEqualTo(201);
        assertThat(txn.getBody()).containsEntry("status", "ROUTED");
        return new String[] { (String) txn.getBody().get("txn_id"),
                              (String) txn.getBody().get("gateway_ref") };
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void initiateCallsSignedLoginAndIntentQr_andMobileGetReturnsBankIntents() {
        String[] ids = createMobileSessionAndTxn();
        String txnId = ids[0];

        // Both /login and /generate-intent-qr must carry a signature header.
        FONEPAY_MOCK.verify(postRequestedFor(urlEqualTo("/api/merchant/third-party/v2/login"))
                .withHeader("signature", WireMock.matching(".+"))
                .withHeader("Authorization", WireMock.matching("Basic .+")));
        FONEPAY_MOCK.verify(postRequestedFor(urlEqualTo("/api/merchant/third-party/v2/generate-intent-qr"))
                .withHeader("signature", WireMock.matching(".+"))
                .withHeader("Authorization", equalTo("Bearer test-fp-bearer"))
                .withRequestBody(matchingJsonPath("$.terminalId", equalTo("TEST-TERMINAL-001"))));

        ResponseEntity<Map> get = http.exchange(
                url("/v1/transactions/" + txnId), HttpMethod.GET,
                new HttpEntity<String>(authHeaders(null, true)), Map.class);
        assertThat(get.getStatusCode().value()).isEqualTo(200);

        Map body = get.getBody();
        assertThat(body).containsEntry("device", "MOBILE");
        assertThat((String) body.get("qr_payload")).startsWith("00020101");
        List<Map<String, Object>> intents = (List<Map<String, Object>>) body.get("intents");
        assertThat(intents).hasSize(2);

        Map<String, Object> first = intents.get(0);
        assertThat(first).containsEntry("bank_name", "Test Bank A");
        assertThat(first).containsEntry("package_name", "com.test.banka");
        assertThat((String) first.get("intent_url")).startsWith("tbka://payment/?qrPayload=");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void pollerFlipsTxnToSettledWhenStatusEndpointReturnsSuccess() {
        String[] ids = createMobileSessionAndTxn();
        String txnId = ids[0];

        // Switch the status stub: success now.
        FONEPAY_MOCK.stubFor(post(urlEqualTo("/api/merchant/third-party/v2/thirdPartyDynamicQrGetStatus"))
                .willReturn(okJson("""
                        {"paymentStatus":"success","paymentMessage":"Payment success","fonepayTraceId":12345}
                        """)));

        // Manually fire one tick instead of waiting for the scheduler.
        poller.tick();

        ResponseEntity<Map> get = http.exchange(
                url("/v1/transactions/" + txnId), HttpMethod.GET,
                new HttpEntity<String>(authHeaders(null, true)), Map.class);
        Map body = get.getBody();
        assertThat(body).containsEntry("status", "SETTLED");
        assertThat(body.get("settled_at")).isNotNull();

        List<Map<String, Object>> events = (List<Map<String, Object>>) body.get("events");
        // ENGINE.txn.created, ENGINE.txn.routed, SYSTEM.payment.settled
        assertThat(events).hasSize(3);
        Map<String, Object> last = events.get(events.size() - 1);
        assertThat(last).containsEntry("type", "payment.settled");
        assertThat(last).containsEntry("source", "SYSTEM");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void pollerKeepsTxnRoutedWhileStatusStillPending() {
        String[] ids = createMobileSessionAndTxn();
        String txnId = ids[0];

        // Default stub returns pending — no state change expected.
        poller.tick();

        ResponseEntity<Map> get = http.exchange(
                url("/v1/transactions/" + txnId), HttpMethod.GET,
                new HttpEntity<String>(authHeaders(null, true)), Map.class);
        assertThat(get.getBody()).containsEntry("status", "ROUTED");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void pollerExpiresTxnPastDeadlineWhileStatusStillNonTerminal() {
        String[] ids = createMobileSessionAndTxn();
        String txnId = ids[0];

        // Status stub stays on the default non-terminal "pending" — exactly what
        // Fonepay keeps returning for an unscanned QR. Backdate the txn deadline
        // so it's already past its expires_at when the poller runs.
        TransactionEntity txn = txnRepo.findByPublicId(txnId).orElseThrow();
        txn.setExpiresAt(Instant.now().minus(1, ChronoUnit.MINUTES));
        txnRepo.save(txn);

        poller.tick();

        ResponseEntity<Map> get = http.exchange(
                url("/v1/transactions/" + txnId), HttpMethod.GET,
                new HttpEntity<String>(authHeaders(null, true)), Map.class);
        Map body = get.getBody();
        assertThat(body).containsEntry("status", "EXPIRED");

        List<Map<String, Object>> events = (List<Map<String, Object>>) body.get("events");
        Map<String, Object> last = events.get(events.size() - 1);
        assertThat(last).containsEntry("type", "payment.expired");
        assertThat(last).containsEntry("source", "SYSTEM");
    }
}
