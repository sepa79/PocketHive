package io.pockethive.orchestrator.infra.scenario;

import io.pockethive.orchestrator.config.OrchestratorHttpProperties;
import io.pockethive.orchestrator.config.OrchestratorNetworkProxyManagerProperties;
import io.pockethive.orchestrator.config.OrchestratorScenarioManagerProperties;
import io.pockethive.orchestrator.config.OrchestratorImageProperties;
import io.pockethive.orchestrator.config.OrchestratorDockerProperties;
import io.pockethive.orchestrator.config.OrchestratorMetricsProperties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.scenarios.api.RuntimeRequest;
import io.pockethive.scenarios.api.ScenarioRuntimeResponse;
import io.pockethive.scenarios.api.VariablesResolveResponse;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.pockethive.auth.client.AuthServiceServiceTokenProvider;
import io.pockethive.manager.runtime.ComputeAdapterType;
import io.pockethive.observability.metrics.PocketHiveMetricsAdapter;
import io.pockethive.orchestrator.app.ScenarioClientException;
import io.pockethive.orchestrator.config.OrchestratorProperties;
import io.pockethive.sink.clickhouse.metrics.ClickHouseMetricsSinkProperties;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class ScenarioManagerClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void scenarioTemplateResponseIgnoresAdditionalTemplateFields() throws Exception {
        String payload = """
            {
              "bundleKey": "e2e/local-rest",
              "bundlePath": "e2e/local-rest",
              "folderPath": "e2e",
              "id": "local-rest",
              "name": "Local REST",
              "description": "demo",
              "controllerImage": "swarm-controller:latest",
              "bees": [],
              "defunct": false,
              "defunctReason": null
            }
            """;

        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/templates/local-rest", exchange -> respondJson(exchange, payload));
        server.start();
        var response = client().fetchScenarioTemplate(" local-rest ");

        assertThat(response.id()).isEqualTo("local-rest");
        assertThat(response.bundleKey()).isEqualTo("e2e/local-rest");
        assertThat(response.bundlePath()).isEqualTo("e2e/local-rest");
        assertThat(response.folderPath()).isEqualTo("e2e");
        assertThat(response.defunct()).isFalse();
    }

    @Test
    void rejectsNullTemplateMetadataRatherThanReturningAnAbsentDescriptor() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/templates/local-rest", exchange -> respondJson(exchange, "null"));
        server.start();

        assertThatThrownBy(() -> client().fetchScenarioTemplate("local-rest"))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void prepareScenarioRuntimeCallsRuntimeEndpointDirectly() throws Exception {
        List<String> calls = new CopyOnWriteArrayList<>();
        List<RuntimeRequest> requests = new CopyOnWriteArrayList<>();
        List<com.fasterxml.jackson.databind.JsonNode> wireRequests = new CopyOnWriteArrayList<>();

        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/scenarios/local-rest/runtime", exchange -> {
            calls.add("runtime");
            var request = objectMapper.readTree(exchange.getRequestBody());
            wireRequests.add(request);
            requests.add(objectMapper.treeToValue(request, RuntimeRequest.class));
            respondJson(exchange, objectMapper.writeValueAsString(
                new ScenarioRuntimeResponse("local-rest", "sw1", "/tmp/runtime/sw1")));
        });
        server.start();

        String runtimeDir = client().prepareScenarioRuntime(" local-rest ", " sw1 ");

        assertThat(runtimeDir).isEqualTo("/tmp/runtime/sw1");
        assertThat(calls).containsExactly("runtime");
        assertThat(requests).containsExactly(new RuntimeRequest("sw1"));
        assertThat(wireRequests).containsExactly(objectMapper.readTree("{\"swarmId\":\"sw1\"}"));
    }

    @Test
    void prepareScenarioRuntimePropagatesRuntimeEndpointFailure() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/scenarios/local-rest/runtime", exchange ->
            respondJson(exchange, 400, """
                    {
                      "ok": false
                    }
                    """));
        server.start();

        assertThatThrownBy(() -> client().prepareScenarioRuntime("local-rest", "sw1"))
            .isInstanceOf(ScenarioClientException.class)
            .hasMessageContaining("scenario-runtime local-rest/sw1 POST status 400")
            .satisfies(error -> {
                ScenarioClientException failure = (ScenarioClientException) error;
                assertThat(failure.statusCode()).isEqualTo(400);
                assertThat(failure.responseBody()).contains("\"ok\": false");
                assertThat(failure.contentType()).contains("application/json");
            });
    }

    @Test
    void resolvesProducerVariablesWithoutLosingValuesWarningsOrRequestContext() throws Exception {
        var values = Map.<String, Object>of("attempts", 3, "enabled", true,
            "nested", Map.of("ids", List.of("a", "b")));
        var warnings = List.of("Profile selected without a SUT override");
        var queries = new CopyOnWriteArrayList<String>();
        var correlations = new CopyOnWriteArrayList<String>();
        var idempotency = new CopyOnWriteArrayList<String>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/scenarios/local-rest/variables/resolve", exchange -> {
            queries.add(exchange.getRequestURI().getRawQuery());
            correlations.add(exchange.getRequestHeaders().getFirst("X-Correlation-Id"));
            idempotency.add(exchange.getRequestHeaders().getFirst("X-Idempotency-Key"));
            respondJson(exchange, objectMapper.writeValueAsString(
                new VariablesResolveResponse("profile A", "sut/A", values, warnings)));
        });
        server.start();

        var resolved = client().resolveScenarioVariables(" local-rest ", " profile A ", " sut/A ", "corr-1", "idem-1");

        assertThat(resolved.profileId()).isEqualTo("profile A");
        assertThat(resolved.sutId()).isEqualTo("sut/A");
        assertThat(resolved.vars()).isEqualTo(values);
        assertThat(resolved.warnings()).isEqualTo(warnings);
        assertThat(queries).containsExactly("profileId=profile+A&sutId=sut%2FA");
        assertThat(correlations).containsExactly("corr-1");
        assertThat(idempotency).containsExactly("idem-1");
    }

    @Test
    void keepsExistingEmptyProjectionForNullVariableCollections() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/scenarios/local-rest/variables/resolve", exchange ->
            respondJson(exchange, objectMapper.writeValueAsString(
                new VariablesResolveResponse(null, null, null, null))));
        server.start();

        var resolved = client().resolveScenarioVariables("local-rest", null, null, null, null);

        assertThat(resolved.profileId()).isNull();
        assertThat(resolved.sutId()).isNull();
        assertThat(resolved.vars()).isEmpty();
        assertThat(resolved.warnings()).isEmpty();
    }

    @Test
    void rejectsProducerResponseWithoutRuntimeDirectory() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/scenarios/local-rest/runtime", exchange ->
            respondJson(exchange, objectMapper.writeValueAsString(
                new ScenarioRuntimeResponse("local-rest", "sw1", "  "))));
        server.start();

        assertThatThrownBy(() -> client().prepareScenarioRuntime("local-rest", "sw1"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("returned empty runtimeDir");
    }

    private ScenarioManagerClient client() {
        URI baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        return new ScenarioManagerClient(
            objectMapper,
            properties(baseUri.toString()),
            new StaticListableBeanFactory(Map.of())
                .getBeanProvider(AuthServiceServiceTokenProvider.class)
        );
    }

    private static OrchestratorProperties properties(String scenarioManagerUrl) {
        OrchestratorHttpProperties http = new OrchestratorHttpProperties(Duration.ofSeconds(2), Duration.ofSeconds(5));
        return new OrchestratorProperties(
            metrics(),
            new OrchestratorDockerProperties("/var/run/docker.sock", ComputeAdapterType.AUTO),
            new OrchestratorImageProperties(null),
            new OrchestratorScenarioManagerProperties(scenarioManagerUrl, http),
            new OrchestratorNetworkProxyManagerProperties("http://network-proxy-manager:8080", http)
        );
    }

    private static OrchestratorMetricsProperties metrics() {
        return new OrchestratorMetricsProperties(
            PocketHiveMetricsAdapter.DISABLED,
            Duration.ofSeconds(10),
            ClickHouseMetricsSinkProperties.disabled()
        );
    }

    private static void respondJson(HttpExchange exchange, String body) throws IOException {
        respondJson(exchange, 200, body);
    }

    private static void respondJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
