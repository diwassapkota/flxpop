package com.flxpop.engine;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuthTest extends AbstractIntegrationTest {

    @Autowired
    TestRestTemplate http;

    @Test
    @SuppressWarnings("rawtypes")
    void rejectsRequestWithoutAuthHeader() {
        HttpHeaders h = new HttpHeaders();
        h.add("Content-Type", "application/json");
        ResponseEntity<Map> res = http.exchange(
                url("/v1/sessions"), HttpMethod.POST,
                new HttpEntity<String>("{\"amount\":1000,\"currency\":\"NPR\",\"country\":\"NP\"}", h),
                Map.class);

        assertThat(res.getStatusCode().value()).isEqualTo(401);
        Map<String, Object> err = (Map<String, Object>) res.getBody().get("error");
        assertThat(err).containsEntry("type", "missing_credentials");
    }

    @Test
    @SuppressWarnings("rawtypes")
    void rejectsInvalidBearerToken() {
        HttpHeaders h = new HttpHeaders();
        h.add("Content-Type", "application/json");
        h.add("Authorization", "Bearer sk_obviously_wrong");
        ResponseEntity<Map> res = http.exchange(
                url("/v1/sessions"), HttpMethod.POST,
                new HttpEntity<String>("{\"amount\":1000,\"currency\":\"NPR\",\"country\":\"NP\"}", h),
                Map.class);

        assertThat(res.getStatusCode().value()).isEqualTo(401);
        Map<String, Object> err = (Map<String, Object>) res.getBody().get("error");
        assertThat(err).containsEntry("type", "invalid_credentials");
    }

    @Test
    @SuppressWarnings("rawtypes")
    void acceptsValidDevKey() {
        HttpHeaders h = new HttpHeaders();
        h.add("Content-Type", "application/json");
        h.add("Authorization", "Bearer " + DEV_KEY);
        ResponseEntity<Map> res = http.exchange(
                url("/v1/sessions"), HttpMethod.POST,
                new HttpEntity<String>("{\"amount\":1000,\"currency\":\"NPR\",\"country\":\"NP\"}", h),
                Map.class);

        assertThat(res.getStatusCode().value()).isEqualTo(201);
        assertThat(res.getBody()).containsKey("session_id");
    }

    @Test
    void healthEndpointIsExemptFromAuth() {
        // No auth header — must still work.
        ResponseEntity<Map<String, Object>> res = http.exchange(
                url("/actuator/health"), HttpMethod.GET, null,
                new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(res.getStatusCode().value()).isEqualTo(200);
    }
}
