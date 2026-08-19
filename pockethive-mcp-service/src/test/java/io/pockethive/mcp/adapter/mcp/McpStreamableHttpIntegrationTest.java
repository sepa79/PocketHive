package io.pockethive.mcp.adapter.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.spec.HttpHeaders;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "pockethive.mcp.pocket-hive-ingress=http://127.0.0.1:8080",
    "pockethive.mcp.owner-api-base=http://127.0.0.1:8080",
    "pockethive.mcp.protocol-revision=2025-11-25",
    "pockethive.mcp.state-mode=MEMORY",
    "pockethive.mcp.state-path=target/mcp-it-state",
    "pockethive.mcp.upload-spool-path=target/mcp-it-spool",
    "pockethive.mcp.open-session-ttl=PT30M",
    "pockethive.mcp.closed-session-retention=PT1H",
    "pockethive.mcp.attempt-retention=PT1H",
    "pockethive.mcp.receipt-retention=PT1H",
    "pockethive.mcp.upload-ticket-ttl=PT5M",
    "pockethive.mcp.max-open-sessions=100",
    "pockethive.mcp.max-open-sessions-per-principal=10",
    "pockethive.mcp.max-workflows-per-session=10",
    "pockethive.mcp.max-state-bytes=10000000",
    "pockethive.mcp.max-concurrent-uploads-per-principal=2",
    "pockethive.mcp.max-concurrent-uploads=10",
    "pockethive.mcp.max-upload-bytes=1000000",
    "pockethive.mcp.max-upload-spool-bytes=2000000",
    "pockethive.mcp.max-archive-files=200",
    "pockethive.mcp.max-archive-expanded-bytes=2000000",
    "pockethive.mcp.max-archive-nesting=8",
    "pockethive.mcp.max-archive-compression-ratio=100",
    "pockethive.mcp.allowed-origins=http://127.0.0.1:*",
    "pockethive.mcp.allowed-hosts=127.0.0.1:*",
    "pockethive.mcp.oauth-issuer=https://issuer.example",
    "pockethive.mcp.oauth-resource=http://127.0.0.1:8080/mcp",
    "pockethive.mcp.oauth-introspection-uri=https://issuer.example/oauth/introspect",
    "pockethive.mcp.oauth-introspection-client-id=mcp",
    "pockethive.mcp.oauth-introspection-client-secret=redacted-test-secret",
    "pockethive.mcp.downstream-service-name=pockethive-mcp",
    "pockethive.mcp.downstream-service-secret=redacted-test-secret"
})
class McpStreamableHttpIntegrationTest {
    private static final String REVISION = "2025-11-25";
    private static final String INITIALIZE = """
        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"integration-test","version":"1.0"}}}
        """;

    @LocalServerPort int port;
    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void routesBinaryUploadsToTheAuthenticatedUploadController() throws Exception {
        byte[] archive = {1, 2, 3};
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                "http://127.0.0.1:" + port + "/mcp/uploads/uv-00000000-0000-0000-0000-000000000000"))
            .header("Authorization", "Bearer qa-token")
            .header("Content-Type", "application/zip")
            .header("Origin", "http://127.0.0.1:" + port)
            .header(HttpHeaders.PROTOCOL_VERSION, REVISION)
            .PUT(HttpRequest.BodyPublishers.ofByteArray(archive))
            .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("UPLOAD_TICKET_NOT_FOUND");
    }

    @Test
    void enforcesRevisionOriginSessionLifecycleAndPrincipalBindingOverHttp() throws Exception {
        HttpResponse<String> initialized = post(INITIALIZE, "qa-token", REVISION, null,
            "http://127.0.0.1:" + port);
        assertThat(initialized.statusCode()).as("initialize response %s headers=%s body=%s", initialized,
                initialized.headers().map(), initialized.body())
            .isEqualTo(200);
        assertThat(initialized.body()).contains("2025-11-25", "pockethive-mcp");
        String sessionId = initialized.headers().firstValue(HttpHeaders.MCP_SESSION_ID).orElseThrow();
        assertThat(sessionId).isNotBlank();

        HttpResponse<String> notified = post(
            "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
            "qa-token", REVISION, sessionId, "http://127.0.0.1:" + port);
        assertThat(notified.statusCode()).isIn(200, 202);

        HttpResponse<String> listed = post(
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}",
            "qa-token", REVISION, sessionId, "http://127.0.0.1:" + port);
        assertThat(listed.statusCode()).isEqualTo(200);
        assertThat(listed.body()).contains("scenario_list", "agent_session_create");

        HttpResponse<String> resources = post(
            "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"resources/list\",\"params\":{}}",
            "qa-token", REVISION, sessionId, "http://127.0.0.1:" + port);
        assertThat(resources.statusCode()).isEqualTo(200);
        assertThat(resources.body()).contains(
            "pockethive://knowledge/overview",
            "pockethive://knowledge/architecture",
            "pockethive://knowledge/scenario-contract",
            "pockethive://capabilities/current",
            "pockethive://tools/catalogue",
            "pockethive://skills/catalogue");

        HttpResponse<String> capabilities = post(
            "{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"resources/read\",\"params\":{\"uri\":\"pockethive://capabilities/current\"}}",
            "qa-token", REVISION, sessionId, "http://127.0.0.1:" + port);
        assertThat(capabilities.statusCode()).isEqualTo(200);
        assertThat(capabilities.body()).contains(
            "qa-lead",
            "integration-test",
            "pockethive:mcp:discover",
            "sha256:",
            "2025-11-25");

        HttpResponse<String> visibleTools = post(
            "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"resources/read\",\"params\":{\"uri\":\"pockethive://tools/catalogue\"}}",
            "qa-token", REVISION, sessionId, "http://127.0.0.1:" + port);
        assertThat(visibleTools.statusCode()).isEqualTo(200);
        assertThat(visibleTools.body()).contains("scenario_list", "agent_session_create")
            .doesNotContain("swarm_start", "runtime_cleanup_execute");

        HttpResponse<String> architecture = post(
            "{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"resources/read\",\"params\":{\"uri\":\"pockethive://knowledge/architecture\"}}",
            "qa-token", REVISION, sessionId, "http://127.0.0.1:" + port);
        assertThat(architecture.statusCode()).isEqualTo(200);
        assertThat(architecture.body()).contains("Authoritative architecture specification");

        try (var stream = getSse("qa-token", REVISION, sessionId).body()) {
            assertThat(stream).isNotNull();
        }

        assertThat(post("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/list\",\"params\":{}}",
            "qa-token", REVISION, null, "http://127.0.0.1:" + port).statusCode()).isEqualTo(400);
        assertThat(post("{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/list\",\"params\":{}}",
            "qa-token", REVISION, "unknown-session", "http://127.0.0.1:" + port).statusCode()).isEqualTo(404);

        assertThat(post(INITIALIZE, "qa-token", "2026-07-28", null,
            "http://127.0.0.1:" + port).statusCode()).isEqualTo(400);
        assertThat(post(INITIALIZE, "qa-token", REVISION, null,
            "https://attacker.example").statusCode()).isEqualTo(403);
        assertThat(post("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\",\"params\":{}}",
            "other-token", REVISION, sessionId, "http://127.0.0.1:" + port).statusCode()).isEqualTo(403);

        HttpResponse<String> deleted = delete("qa-token", REVISION, sessionId);
        assertThat(deleted.statusCode()).isIn(200, 204);
        assertThat(post("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/list\",\"params\":{}}",
            "qa-token", REVISION, sessionId, "http://127.0.0.1:" + port).statusCode()).isEqualTo(404);
    }

    private HttpResponse<String> post(String body, String token, String revision,
                                      String sessionId, String origin) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .header("Origin", origin)
            .header(HttpHeaders.PROTOCOL_VERSION, revision)
            .POST(HttpRequest.BodyPublishers.ofString(body));
        if (sessionId != null) {
            request.header(HttpHeaders.MCP_SESSION_ID, sessionId);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> delete(String token, String revision, String sessionId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
            .header("Authorization", "Bearer " + token)
            .header("Origin", "http://127.0.0.1:" + port)
            .header(HttpHeaders.PROTOCOL_VERSION, revision)
            .header(HttpHeaders.MCP_SESSION_ID, sessionId)
            .DELETE()
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<java.io.InputStream> getSse(String token, String revision,
                                                      String sessionId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
            .header("Authorization", "Bearer " + token)
            .header("Accept", "text/event-stream")
            .header("Origin", "http://127.0.0.1:" + port)
            .header(HttpHeaders.PROTOCOL_VERSION, revision)
            .header(HttpHeaders.MCP_SESSION_ID, sessionId)
            .GET()
            .build();
        HttpResponse<java.io.InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
            .startsWith("text/event-stream");
        return response;
    }

    @TestConfiguration
    static class AuthenticationConfiguration {
        @Bean
        @Primary
        OpaqueTokenIntrospector testTokens(
            @Value("${pockethive.mcp.oauth-resource}") String audience) {
            return token -> {
                String subject = "other-token".equals(token)
                    ? "22222222-2222-2222-2222-222222222222"
                    : "11111111-1111-1111-1111-111111111111";
                String username = "other-token".equals(token) ? "other" : "qa-lead";
                List<GrantedAuthority> authorities = List.of(
                    new SimpleGrantedAuthority("SCOPE_pockethive:mcp:discover"),
                    new SimpleGrantedAuthority("SCOPE_pockethive:mcp:read"),
                    new SimpleGrantedAuthority("SCOPE_pockethive:mcp:author"));
                return new DefaultOAuth2AuthenticatedPrincipal(subject, Map.of(
                    "iss", "https://issuer.example",
                    "sub", subject,
                    "username", username,
                    "client_id", "integration-test",
                    "scope", "pockethive:mcp:discover pockethive:mcp:read pockethive:mcp:author",
                    "aud", List.of(audience),
                    "exp", Instant.now().plusSeconds(300)), authorities);
            };
        }
    }
}
