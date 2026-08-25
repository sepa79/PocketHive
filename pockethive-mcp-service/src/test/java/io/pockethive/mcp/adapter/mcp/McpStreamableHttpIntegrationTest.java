package io.pockethive.mcp.adapter.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.HttpHeaders;
import io.pockethive.auth.contract.PocketHiveMcpScopes;
import io.pockethive.mcp.application.BundleUploadContract;
import io.pockethive.mcp.application.BundleUploadCoordinator;
import io.pockethive.mcp.application.PreparedUpload;
import io.pockethive.mcp.application.ValidationUploadTicket;
import io.pockethive.mcp.domain.BundleFileManifest;
import io.pockethive.mcp.domain.PrincipalKey;
import io.pockethive.mcp.domain.SourceMetadata;
import io.pockethive.mcp.domain.SourceVerification;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "pockethive.mcp.pocket-hive-ingress=http://127.0.0.1:8080",
    "pockethive.mcp.owner-api-base=http://127.0.0.1:8080",
    "pockethive.mcp.environment-health.probe-timeout=PT2S",
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
    "pockethive.mcp.max-transport-sessions=100",
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

    @LocalServerPort int port;
    @Autowired BundleUploadCoordinator uploads;
    private final HttpClient client = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void publishesExactlyTheInteractiveDynamicallyRegisterableScopes() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                "http://127.0.0.1:" + port + "/.well-known/oauth-protected-resource"))
            .GET()
            .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode metadata = mapper.readTree(response.body());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(mapper.convertValue(metadata.path("scopes_supported"), new TypeReference<List<String>>() { }))
            .containsExactlyElementsOf(PocketHiveMcpScopes.COMPANION_ORDERED);
    }

    @Test
    void advertisesProtectedResourceDiscoveryInTheBearerChallenge() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .header("Origin", "http://127.0.0.1:" + port)
            .POST(HttpRequest.BodyPublishers.ofString(initialize(REVISION)))
            .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("WWW-Authenticate")).hasValueSatisfying(challenge ->
            assertThat(challenge)
                .startsWith("Bearer ")
                .contains("resource_metadata=\"http://127.0.0.1:8080/.well-known/oauth-protected-resource\"")
                .doesNotContain("Basic"));
    }

    @Test
    void delegatesSupportedAndUnknownInitializationRevisionNegotiationToThePinnedSdk() throws Exception {
        HttpResponse<String> older = post(initialize("2025-06-18"), "qa-token", "2025-06-18", null,
            "http://127.0.0.1:" + port);
        assertThat(older.statusCode()).isEqualTo(200);
        assertThat(older.body()).contains("2025-06-18");
        delete("qa-token", "2025-06-18",
            older.headers().firstValue(HttpHeaders.MCP_SESSION_ID).orElseThrow());

        HttpResponse<String> unknown = post(initialize("2026-07-28"), "qa-token", "2026-07-28", null,
            "http://127.0.0.1:" + port);
        assertThat(unknown.statusCode()).isEqualTo(200);
        assertThat(unknown.body()).contains("2025-11-25");
        delete("qa-token", "2026-07-28",
            unknown.headers().firstValue(HttpHeaders.MCP_SESSION_ID).orElseThrow());
    }

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
    void acceptsOnlyTheExactOneUseUploadCapabilityWhenBearerAuthenticationIsAbsent() throws Exception {
        PreparedUpload<ValidationUploadTicket> prepared = uploads.prepareDirectValidationWithCapability(
            new PrincipalKey(URI.create("https://issuer.example"), "qa-lead"),
            new SourceMetadata("https://git.example/repo", "a".repeat(40), "scenarios/sample",
                SourceVerification.CLIENT_ASSERTED),
            new BundleFileManifest(List.of()), Instant.now());
        URI uploadUrl = URI.create("http://127.0.0.1:" + port + prepared.ticket().uploadPath());
        byte[] invalidArchive = {1, 2, 3};

        HttpResponse<String> invalid = upload(uploadUrl, invalidArchive, "wrong-capability", null);
        assertThat(invalid.statusCode()).isEqualTo(401);
        assertThat(invalid.body()).contains("UPLOAD_AUTHENTICATION_REQUIRED")
            .doesNotContain(prepared.ticket().id());

        HttpResponse<String> accepted = upload(uploadUrl, invalidArchive, prepared.uploadCapability(), null);
        assertThat(accepted.statusCode()).isEqualTo(400);
        assertThat(accepted.body()).doesNotContain("UPLOAD_AUTHENTICATION");

        HttpResponse<String> replay = upload(uploadUrl, invalidArchive, prepared.uploadCapability(), null);
        assertThat(replay.statusCode()).isEqualTo(400);
        assertThat(replay.body()).contains("UPLOAD_TICKET_CONSUMED");
    }

    @Test
    void rejectsQueryCapabilitiesAndAmbiguousBearerPlusCapabilityAuthentication() throws Exception {
        PreparedUpload<ValidationUploadTicket> queryTicket = uploadTicket();
        URI queryUrl = URI.create("http://127.0.0.1:" + port + queryTicket.ticket().uploadPath()
            + "?uploadCapability=not-a-capability");
        HttpResponse<String> query = upload(queryUrl, new byte[] {1}, null, null);
        assertThat(query.statusCode()).isEqualTo(401);

        PreparedUpload<ValidationUploadTicket> ambiguousTicket = uploadTicket();
        URI ambiguousUrl = URI.create("http://127.0.0.1:" + port + ambiguousTicket.ticket().uploadPath());
        HttpResponse<String> ambiguous = upload(ambiguousUrl, new byte[] {1},
            ambiguousTicket.uploadCapability(), "qa-token");
        assertThat(ambiguous.statusCode()).isEqualTo(400);
        assertThat(ambiguous.body()).contains("UPLOAD_AUTHENTICATION_AMBIGUOUS");

        PreparedUpload<ValidationUploadTicket> invalidBearerTicket = uploadTicket();
        URI invalidBearerUrl = URI.create(
            "http://127.0.0.1:" + port + invalidBearerTicket.ticket().uploadPath());
        HttpResponse<String> invalidBearer = upload(invalidBearerUrl, new byte[] {1},
            invalidBearerTicket.uploadCapability(), "invalid-token");
        assertThat(invalidBearer.statusCode()).isEqualTo(401);
        HttpResponse<String> capabilityAfterRejectedBearer = upload(invalidBearerUrl, new byte[] {1},
            invalidBearerTicket.uploadCapability(), null);
        assertThat(capabilityAfterRejectedBearer.statusCode()).isEqualTo(400);
        assertThat(capabilityAfterRejectedBearer.body()).doesNotContain("UPLOAD_AUTHENTICATION");
    }

    @Test
    void enforcesRevisionOriginSessionLifecycleAndPrincipalBindingOverHttp() throws Exception {
        HttpResponse<String> initialized = post(initialize(REVISION), "qa-token", REVISION, null,
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
        assertThat(listed.body()).contains(
            "scenario_list", "agent_session_create", "scenario_workflow_question",
            "scenario_workflow_answer_submit", "scenario_workflow_review_prepare",
            "scenario_workflow_review_submit");

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
            "pockethive://skills/catalogue",
            "pockethive://environment/health");

        HttpResponse<String> capabilities = post(
            "{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"resources/read\",\"params\":{\"uri\":\"pockethive://capabilities/current\"}}",
            "qa-token", REVISION, sessionId, "http://127.0.0.1:" + port);
        assertThat(capabilities.statusCode()).isEqualTo(200);
        assertThat(capabilities.body()).contains(
            "qa-lead",
            "integration-test",
            "pockethive:mcp:discover",
            "qaAnswerCaptureModes",
            "MCP_FORM",
            "AGENT_MEDIATED",
            "COMPACT_REVIEW",
            "sha256:",
            "2025-11-25");

        HttpResponse<String> visibleTools = post(
            "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"resources/read\",\"params\":{\"uri\":\"pockethive://tools/catalogue\"}}",
            "qa-token", REVISION, sessionId, "http://127.0.0.1:" + port);
        assertThat(visibleTools.statusCode()).isEqualTo(200);
        assertThat(visibleTools.body()).contains(
            "scenario_list", "agent_session_create", "scenario_workflow_question",
            "scenario_workflow_answer_submit", "scenario_workflow_review_prepare",
            "scenario_workflow_review_submit")
            .doesNotContain("swarm_start", "runtime_cleanup_execute");

        HttpResponse<String> environmentHealth = post(
            "{\"jsonrpc\":\"2.0\",\"id\":11,\"method\":\"resources/read\",\"params\":{\"uri\":\"pockethive://environment/health\"}}",
            "qa-token", REVISION, sessionId, "http://127.0.0.1:" + port);
        assertThat(environmentHealth.statusCode()).isEqualTo(200);
        assertThat(environmentHealth.body()).contains(
            "UNAVAILABLE", "pockethive-ui", "orchestrator", "scenario-manager",
            "network-proxy-manager", "wiremock", "tcp-mock", "grafana");

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

        assertThat(post(initialize(REVISION), "qa-token", REVISION, null,
            "https://attacker.example").statusCode()).isEqualTo(403);
        assertThat(post("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\",\"params\":{}}",
            "other-token", REVISION, sessionId, "http://127.0.0.1:" + port).statusCode()).isEqualTo(403);

        HttpResponse<String> deleted = delete("qa-token", REVISION, sessionId);
        assertThat(deleted.statusCode()).isIn(200, 204);
        assertThat(post("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/list\",\"params\":{}}",
            "qa-token", REVISION, sessionId, "http://127.0.0.1:" + port).statusCode()).isEqualTo(404);
    }

    @Test
    void completesAgentMediatedQuestionAndAnswerOverStreamableHttpWithoutFormCapability() throws Exception {
        HttpResponse<String> initialized = post(initialize(REVISION), "qa-token", REVISION, null,
            "http://127.0.0.1:" + port);
        String transportSessionId = initialized.headers().firstValue(HttpHeaders.MCP_SESSION_ID).orElseThrow();
        post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
            "qa-token", REVISION, transportSessionId, "http://127.0.0.1:" + port);

        JsonNode session = toolResult(post(toolCall(20, "agent_session_create", Map.of()),
            "qa-token", REVISION, transportSessionId, "http://127.0.0.1:" + port));
        String agentSessionId = session.path("agentSessionId").asText();
        assertThat(agentSessionId).startsWith("as-");

        JsonNode workflow = toolResult(post(toolCall(21, "scenario_workflow_create", Map.of(
                "agentSessionId", agentSessionId,
                "expectedSessionRevision", 0)),
            "qa-token", REVISION, transportSessionId, "http://127.0.0.1:" + port));
        String workflowId = workflow.path("workflowId").asText();

        JsonNode question = toolResult(post(toolCall(22, "scenario_workflow_question", Map.of(
                "workflowId", workflowId,
                "topic", "GOAL_AND_RISK")),
            "qa-token", REVISION, transportSessionId, "http://127.0.0.1:" + port));
        assertThat(question.path("captureMode").asText()).isEqualTo("AGENT_MEDIATED");
        assertThat(question.path("message").asText())
            .isEqualTo("What goal, risks, scope, and out-of-scope behaviour must this test cover?");

        JsonNode answered = toolResult(post(toolCall(23, "scenario_workflow_answer_submit", Map.of(
                "workflowId", workflowId,
                "expectedRevision", question.path("workflowRevision").asLong(),
                "topic", "GOAL_AND_RISK",
                "questionId", question.path("questionId").asText(),
                "requestedSchemaDigest", question.path("requestedSchemaDigest").asText(),
                "disposition", "USER_PROVIDED",
                "answer", "Provision cardholders for a later performance test.")),
            "qa-token", REVISION, transportSessionId, "http://127.0.0.1:" + port));
        assertThat(answered.path("revision").asLong()).isEqualTo(1);
        assertThat(answered.path("requirements").path("GOAL_AND_RISK").path("value").asText())
            .isEqualTo("Provision cardholders for a later performance test.");
        assertThat(answered.path("requirements").path("GOAL_AND_RISK")
            .path("provenance").path("questionId").asText())
            .isEqualTo("agent-mediated/goal_and_risk");

        delete("qa-token", REVISION, transportSessionId);
    }

    @Test
    void completesOneAtomicCompactReviewOverStreamableHttp() throws Exception {
        HttpResponse<String> initialized = post(initialize(REVISION), "qa-token", REVISION, null,
            "http://127.0.0.1:" + port);
        String transportSessionId = initialized.headers().firstValue(HttpHeaders.MCP_SESSION_ID).orElseThrow();
        post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
            "qa-token", REVISION, transportSessionId, "http://127.0.0.1:" + port);

        JsonNode session = toolResult(post(toolCall(30, "agent_session_create", Map.of()),
            "qa-token", REVISION, transportSessionId, "http://127.0.0.1:" + port));
        JsonNode workflow = toolResult(post(toolCall(31, "scenario_workflow_create", Map.of(
                "agentSessionId", session.path("agentSessionId").asText(),
                "expectedSessionRevision", 0)),
            "qa-token", REVISION, transportSessionId, "http://127.0.0.1:" + port));
        String workflowId = workflow.path("workflowId").asText();
        List<Map<String, Object>> answers = java.util.Arrays.stream(
                io.pockethive.mcp.domain.QaRequirementTopic.values())
            .map(topic -> Map.<String, Object>of(
                "topic", topic.name(),
                "disposition", "USER_CONFIRMED_SOURCE",
                "answer", "Explicit " + topic.name()))
            .toList();
        Map<String, Object> candidate = new java.util.LinkedHashMap<>();
        candidate.put("workflowId", workflowId);
        candidate.put("expectedRevision", 0);
        candidate.put("sourceName", "user requirement narrative");
        candidate.put("sourceDigest", "sha256:" + "a".repeat(64));
        candidate.put("answers", answers);

        JsonNode review = toolResult(post(toolCall(32, "scenario_workflow_review_prepare", candidate),
            "qa-token", REVISION, transportSessionId, "http://127.0.0.1:" + port));
        assertThat(review.path("captureMode").asText()).isEqualTo("COMPACT_REVIEW");
        assertThat(review.path("message").asText()).contains("Review every requirement below");

        candidate.put("reviewId", review.path("reviewId").asText());
        candidate.put("requestedSchemaDigest", review.path("requestedSchemaDigest").asText());
        candidate.put("answerSetDigest", review.path("answerSetDigest").asText());
        JsonNode submitted = toolResult(post(toolCall(33, "scenario_workflow_review_submit", candidate),
            "qa-token", REVISION, transportSessionId, "http://127.0.0.1:" + port));

        assertThat(submitted.path("revision").asLong()).isEqualTo(1);
        assertThat(submitted.path("state").asText()).isEqualTo("REVIEW_REQUIRED");
        assertThat(submitted.path("requirements").size())
            .isEqualTo(io.pockethive.mcp.domain.QaRequirementTopic.values().length);

        delete("qa-token", REVISION, transportSessionId);
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

    private PreparedUpload<ValidationUploadTicket> uploadTicket() {
        return uploads.prepareDirectValidationWithCapability(
            new PrincipalKey(URI.create("https://issuer.example"), "qa-lead"),
            new SourceMetadata("https://git.example/repo", "a".repeat(40), "scenarios/sample",
                SourceVerification.CLIENT_ASSERTED),
            new BundleFileManifest(List.of()), Instant.now());
    }

    private HttpResponse<String> upload(URI uploadUrl, byte[] archive, String capability,
                                        String bearerToken) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uploadUrl)
            .header("Content-Type", "application/zip")
            .PUT(HttpRequest.BodyPublishers.ofByteArray(archive));
        if (capability != null) {
            request.header(BundleUploadContract.UPLOAD_CAPABILITY_HEADER, capability);
        }
        if (bearerToken != null) {
            request.header("Authorization", "Bearer " + bearerToken);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String initialize(String revision) {
        return """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"%s","capabilities":{},"clientInfo":{"name":"integration-test","version":"1.0"}}}
            """.formatted(revision);
    }

    private String toolCall(int id, String name, Map<String, Object> arguments) throws Exception {
        return mapper.writeValueAsString(Map.of(
            "jsonrpc", "2.0",
            "id", id,
            "method", "tools/call",
            "params", Map.of("name", name, "arguments", arguments)));
    }

    private JsonNode toolResult(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).isEqualTo(200);
        String data = response.body().lines()
            .filter(line -> line.startsWith("data: "))
            .map(line -> line.substring("data: ".length()))
            .findFirst()
            .orElseThrow();
        JsonNode result = mapper.readTree(data).path("result");
        assertThat(result.path("isError").asBoolean()).isFalse();
        assertThat(result.path("structuredContent").isObject()).isTrue();
        return result.path("structuredContent");
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
                if ("invalid-token".equals(token)) {
                    throw new OAuth2AuthenticationException(new OAuth2Error("invalid_token"));
                }
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
