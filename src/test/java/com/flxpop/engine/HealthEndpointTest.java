package com.flxpop.engine;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HealthEndpointTest extends AbstractIntegrationTest {

    @Autowired
    TestRestTemplate http;

    @Test
    void healthIsUpAndPublic() {
        @SuppressWarnings("rawtypes")
        ResponseEntity<Map> res = http.getForEntity(url("/actuator/health"), Map.class);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody()).containsEntry("status", "UP");
    }
}
