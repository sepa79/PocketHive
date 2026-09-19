package io.pockethive.mcp.adapter.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.mcp.application.EnvironmentHealthContract;
import io.pockethive.mcp.application.EnvironmentHealthTarget;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class IngressEnvironmentHealthProbeTest {
    @Test
    void acceptsOnlyTheDeclaredResponseContractForEachExplicitTarget() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://owner.internal:8088");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        IngressEnvironmentHealthProbe probe = new IngressEnvironmentHealthProbe(
            builder.build(), new ObjectMapper());

        server.expect(requestTo("http://owner.internal:8088/healthz"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("ok", MediaType.TEXT_PLAIN));
        server.expect(requestTo("http://owner.internal:8088/orchestrator/actuator/health"))
            .andRespond(withSuccess("{\"status\":\"UP\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://owner.internal:8088/wiremock/__admin/health"))
            .andRespond(withSuccess("{\"status\":\"healthy\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://owner.internal:8088/grafana/api/health"))
            .andRespond(withSuccess("{\"database\":\"ok\"}", MediaType.APPLICATION_JSON));

        assertThat(probe.healthy(target("/healthz", EnvironmentHealthContract.PLAIN_OK))).isTrue();
        assertThat(probe.healthy(target("/orchestrator/actuator/health",
            EnvironmentHealthContract.SPRING_UP))).isTrue();
        assertThat(probe.healthy(target("/wiremock/__admin/health",
            EnvironmentHealthContract.WIREMOCK_HEALTHY))).isTrue();
        assertThat(probe.healthy(target("/grafana/api/health",
            EnvironmentHealthContract.GRAFANA_DATABASE_OK))).isTrue();
        server.verify();
    }

    @Test
    void mapsMalformedUnexpectedAndUnavailableResponsesToFalseWithoutAnotherRequest() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://owner.internal:8088");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        IngressEnvironmentHealthProbe probe = new IngressEnvironmentHealthProbe(
            builder.build(), new ObjectMapper());

        server.expect(requestTo("http://owner.internal:8088/plain"))
            .andRespond(withSuccess("almost ok", MediaType.TEXT_PLAIN));
        server.expect(requestTo("http://owner.internal:8088/json"))
            .andRespond(withSuccess("not-json", MediaType.TEXT_PLAIN));
        server.expect(requestTo("http://owner.internal:8088/down"))
            .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo("http://owner.internal:8088/disconnected"))
            .andRespond(withException(new java.io.IOException("connection lost")));

        assertThat(probe.healthy(target("/plain", EnvironmentHealthContract.PLAIN_OK))).isFalse();
        assertThat(probe.healthy(target("/json", EnvironmentHealthContract.SPRING_UP))).isFalse();
        assertThat(probe.healthy(target("/down", EnvironmentHealthContract.WIREMOCK_HEALTHY))).isFalse();
        assertThat(probe.healthy(target("/disconnected", EnvironmentHealthContract.GRAFANA_DATABASE_OK))).isFalse();
        server.verify();
    }

    private static EnvironmentHealthTarget target(String probePath, EnvironmentHealthContract contract) {
        return new EnvironmentHealthTarget(
            "test", "Test", URI.create("/test/"), probePath, contract);
    }
}
