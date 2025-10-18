package io.pockethive.scenarios;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "rabbitmq.logging.enabled=false")
class HealthEndpointTest {

    @Autowired
    TestRestTemplate rest;

    @Test
    void healthEndpointReportsUp() {
        ResponseEntity<String> resp = rest.getForEntity("/actuator/health", String.class);
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(resp.getBody()).contains("UP");
    }
}
