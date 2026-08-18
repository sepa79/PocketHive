package io.pockethive.mcp.adapter.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.mcp.application.ToolExecutionException;
import io.pockethive.mcp.config.PocketHiveMcpProperties;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class OwnerApiClientTest {
    @Test
    void sendsAnIsolatedServiceTokenAndRequiresStructuredJsonResponses() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DownstreamTokenProvider tokens = mock(DownstreamTokenProvider.class);
        when(tokens.bearerToken()).thenReturn("service-token");
        OwnerApiClient client = new OwnerApiClient(builder, properties(), tokens, new ObjectMapper());

        server.expect(requestTo("http://owner.internal:8088/orchestrator/api/swarms"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer service-token"))
            .andRespond(withSuccess("{\"items\":[{\"id\":\"swarm-a\"}]}", MediaType.APPLICATION_JSON));

        JsonNode result = (JsonNode) client.get("/orchestrator/api/swarms");
        assertThat(result.path("items").get(0).path("id").asText()).isEqualTo("swarm-a");
        server.verify();
    }

    @Test
    void mapsAnExplicitEmptyOwnerResponseButNeverFallsBackForMalformedJson() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DownstreamTokenProvider tokens = mock(DownstreamTokenProvider.class);
        when(tokens.bearerToken()).thenReturn("service-token");
        OwnerApiClient client = new OwnerApiClient(builder, properties(), tokens, new ObjectMapper());

        server.expect(requestTo("http://owner.internal:8088/empty"))
            .andRespond(withSuccess("", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://owner.internal:8088/malformed"))
            .andRespond(withSuccess("not-json", MediaType.TEXT_PLAIN));

        assertThat(client.delete("/empty")).isEqualTo(Map.of());
        assertThatThrownBy(() -> client.post("/malformed", Map.of("intent", "exact")))
            .isInstanceOfSatisfying(ToolExecutionException.class, exception -> {
                assertThat(exception.code()).isEqualTo("OWNER_RESPONSE_INVALID");
                assertThat(exception).hasMessage("Owner response was not valid JSON");
            });
        server.verify();
    }

    @Test
    void distinguishesDefinitiveRejectionReadFailureAndAmbiguousMutationWithoutRetry() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DownstreamTokenProvider tokens = mock(DownstreamTokenProvider.class);
        when(tokens.bearerToken()).thenReturn("service-token");
        OwnerApiClient client = new OwnerApiClient(builder, properties(), tokens, new ObjectMapper());

        server.expect(requestTo("http://owner.internal:8088/rejected"))
            .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo("http://owner.internal:8088/unavailable"))
            .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo("http://owner.internal:8088/ambiguous"))
            .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo("http://owner.internal:8088/disconnected"))
            .andRespond(withException(new java.io.IOException("connection lost")));

        assertCode("OWNER_REQUEST_REJECTED", () -> client.get("/rejected"));
        assertCode("OWNER_UNAVAILABLE", () -> client.get("/unavailable"));
        assertCode("OWNER_RESULT_AMBIGUOUS", () -> client.post("/ambiguous", Map.of("intent", "exact")));
        assertCode("OWNER_RESULT_AMBIGUOUS", () -> client.delete("/disconnected"));
        server.verify();
    }

    private static void assertCode(String code, Runnable call) {
        assertThatThrownBy(call::run)
            .isInstanceOfSatisfying(ToolExecutionException.class,
                exception -> assertThat(exception.code()).isEqualTo(code));
    }

    private static PocketHiveMcpProperties properties() {
        PocketHiveMcpProperties properties = mock(PocketHiveMcpProperties.class);
        when(properties.ownerApiBase()).thenReturn(URI.create("http://owner.internal:8088"));
        return properties;
    }
}
