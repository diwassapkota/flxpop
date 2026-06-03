package com.flxpop.engine;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SessionFlowTest extends AbstractIntegrationTest {

    @Autowired
    TestRestTemplate http;

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void desktopUserGetsDesktopRoutedMethodsForNepal() {
        HttpHeaders h = new HttpHeaders();
        h.add("Content-Type", "application/json");
        h.add("Authorization", "Bearer " + DEV_KEY);
        // Sec-CH-UA-Mobile=?0 forces DESKTOP regardless of test UA.
        h.add("Sec-CH-UA-Mobile", "?0");

        ResponseEntity<Map> res = http.exchange(
                url("/v1/sessions"), HttpMethod.POST,
                new HttpEntity<String>(
                        "{\"amount\":250000,\"currency\":\"NPR\",\"country\":\"NP\",\"reference\":\"ORDER-A\"}", h),
                Map.class);

        assertThat(res.getStatusCode().value()).isEqualTo(201);
        Map body = res.getBody();
        assertThat(body).containsEntry("country", "NP");
        assertThat(body).containsEntry("device",  "DESKTOP");
        assertThat(body).containsEntry("currency", "NPR");
        assertThat(((Number) body.get("amount")).longValue()).isEqualTo(250000L);

        List<Map<String, Object>> methods = (List<Map<String, Object>>) body.get("methods");
        assertThat(methods).hasSize(2);
        assertThat(methods.get(0)).containsEntry("gateway", "FONEPAY");
        assertThat(methods.get(1)).containsEntry("gateway", "ESEWA");
        assertThat(((String) body.get("session_id"))).startsWith("SES-");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void mobileClientHintForcesMobileRouting() {
        HttpHeaders h = new HttpHeaders();
        h.add("Content-Type", "application/json");
        h.add("Authorization", "Bearer " + DEV_KEY);
        h.add("Sec-CH-UA-Mobile", "?1");

        ResponseEntity<Map> res = http.exchange(
                url("/v1/sessions"), HttpMethod.POST,
                new HttpEntity<String>("{\"amount\":1000,\"currency\":\"NPR\",\"country\":\"NP\"}", h),
                Map.class);

        assertThat(res.getStatusCode().value()).isEqualTo(201);
        assertThat(res.getBody()).containsEntry("device", "MOBILE");
    }

    @Test
    @SuppressWarnings("rawtypes")
    void currencyCountryMismatchIsRejected() {
        HttpHeaders h = new HttpHeaders();
        h.add("Content-Type", "application/json");
        h.add("Authorization", "Bearer " + DEV_KEY);

        ResponseEntity<Map> res = http.exchange(
                url("/v1/sessions"), HttpMethod.POST,
                new HttpEntity<String>("{\"amount\":1000,\"currency\":\"INR\",\"country\":\"NP\"}", h),
                Map.class);

        assertThat(res.getStatusCode().value()).isEqualTo(400);
        Map<String, Object> err = (Map<String, Object>) res.getBody().get("error");
        assertThat(err).containsEntry("type", "bad_request");
    }
}
