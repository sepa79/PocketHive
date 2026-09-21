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
import io.pockethive.auth.client.AuthServiceServiceTokenProvider;
import io.pockethive.mcp.application.ToolExecutionException;
import io.pockethive.mcp.config.PocketHiveMcpProperties;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
        AuthServiceServiceTokenProvider tokens = mock(AuthServiceServiceTokenProvider.class);
        when(tokens.getAuthorizationHeader()).thenReturn("Bearer service-token");
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
    void preservesExplicitOwnerTextForPreviewReadTools() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AuthServiceServiceTokenProvider tokens = mock(AuthServiceServiceTokenProvider.class);
        when(tokens.getAuthorizationHeader()).thenReturn("Bearer service-token");
        OwnerApiClient client = new OwnerApiClient(builder, properties(), tokens, new ObjectMapper());

        server.expect(requestTo("http://owner.internal:8088/scenarios/scenario-a/raw"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer service-token"))
            .andRespond(withSuccess("id: scenario-a\n", MediaType.TEXT_PLAIN));
        server.expect(requestTo("http://owner.internal:8088/scenarios/scenario-a/schema?path=body.json"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer service-token"))
            .andRespond(withSuccess("{\"type\":\"object\"}", MediaType.APPLICATION_JSON));

        assertThat(client.getText("/scenarios/scenario-a/raw")).isEqualTo("id: scenario-a\n");
        assertThat(client.getText("/scenarios/scenario-a/schema?path=body.json")).isEqualTo("{\"type\":\"object\"}");
        server.verify();
    }

    @Test
    void mapsAnExplicitEmptyOwnerResponseButNeverFallsBackForMalformedJson() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AuthServiceServiceTokenProvider tokens = mock(AuthServiceServiceTokenProvider.class);
        when(tokens.getAuthorizationHeader()).thenReturn("Bearer service-token");
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
        AuthServiceServiceTokenProvider tokens = mock(AuthServiceServiceTokenProvider.class);
        when(tokens.getAuthorizationHeader()).thenReturn("Bearer service-token");
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

    @Test
    void refreshesTheCanonicalServiceTokenAndRepeatsTheExactRequestOnceAfterUnauthorized() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AuthServiceServiceTokenProvider tokens = mock(AuthServiceServiceTokenProvider.class);
        when(tokens.getAuthorizationHeader()).thenReturn("Bearer stale-token");
        when(tokens.refreshAuthorizationHeader()).thenReturn("Bearer fresh-token");
        OwnerApiClient client = new OwnerApiClient(builder, properties(), tokens, new ObjectMapper());

        server.expect(requestTo("http://owner.internal:8088/scenario-manager/api/capabilities"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer stale-token"))
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        server.expect(requestTo("http://owner.internal:8088/scenario-manager/api/capabilities"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer fresh-token"))
            .andRespond(withSuccess("[{\"role\":\"generator\"}]", MediaType.APPLICATION_JSON));

        JsonNode result = (JsonNode) client.get("/scenario-manager/api/capabilities");

        assertThat(result.get(0).path("role").asText()).isEqualTo("generator");
        org.mockito.Mockito.verify(tokens).refreshAuthorizationHeader();
        server.verify();
    }

    @Test
    void returnsTheSecondUnauthorizedWithoutAnotherRefreshOrIdentityFallback() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AuthServiceServiceTokenProvider tokens = mock(AuthServiceServiceTokenProvider.class);
        when(tokens.getAuthorizationHeader()).thenReturn("Bearer stale-token");
        when(tokens.refreshAuthorizationHeader()).thenReturn("Bearer fresh-token");
        OwnerApiClient client = new OwnerApiClient(builder, properties(), tokens, new ObjectMapper());

        server.expect(requestTo("http://owner.internal:8088/orchestrator/api/swarms"))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer stale-token"))
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        server.expect(requestTo("http://owner.internal:8088/orchestrator/api/swarms"))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer fresh-token"))
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertCode("OWNER_REQUEST_REJECTED", () -> client.get("/orchestrator/api/swarms"));
        org.mockito.Mockito.verify(tokens).refreshAuthorizationHeader();
        server.verify();
    }

    @Test
    void refreshesOnceForAnUnauthorizedArchiveUploadAndMapsTheFinalOwnerFailure(@TempDir Path directory)
        throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AuthServiceServiceTokenProvider tokens = mock(AuthServiceServiceTokenProvider.class);
        when(tokens.getAuthorizationHeader()).thenReturn("Bearer stale-token");
        when(tokens.refreshAuthorizationHeader()).thenReturn("Bearer fresh-token");
        OwnerApiClient client = new OwnerApiClient(builder, properties(), tokens, new ObjectMapper());
        Path archive = Files.write(directory.resolve("bundle.zip"), new byte[] {1, 2, 3});

        server.expect(requestTo("http://owner.internal:8088/scenario-manager/api/bundles"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer stale-token"))
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        server.expect(requestTo("http://owner.internal:8088/scenario-manager/api/bundles"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer fresh-token"))
            .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertCode("OWNER_RESULT_AMBIGUOUS",
            () -> client.postZip("/scenario-manager/api/bundles", archive));
        org.mockito.Mockito.verify(tokens).refreshAuthorizationHeader();
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
