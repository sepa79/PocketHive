package io.pockethive.orchestrator.infra.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
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

        ScenarioManagerClient.ScenarioTemplateResponse response =
            objectMapper.readValue(payload, ScenarioManagerClient.ScenarioTemplateResponse.class);

        assertThat(response.id()).isEqualTo("local-rest");
        assertThat(response.bundleKey()).isEqualTo("e2e/local-rest");
        assertThat(response.bundlePath()).isEqualTo("e2e/local-rest");
        assertThat(response.folderPath()).isEqualTo("e2e");
        assertThat(response.defunct()).isFalse();
    }

    @Test
    void prepareScenarioRuntimeCallsRuntimeEndpointDirectly() throws Exception {
        List<String> calls = new CopyOnWriteArrayList<>();

        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/scenarios/local-rest/runtime", exchange -> {
            calls.add("runtime");
            respondJson(exchange, """
                {
                  "scenarioId": "local-rest",
                  "swarmId": "sw1",
                  "runtimeDir": "/tmp/runtime/sw1"
                }
                """);
        });
        server.start();

        String runtimeDir = client().prepareScenarioRuntime(" local-rest ", " sw1 ");

        assertThat(runtimeDir).isEqualTo("/tmp/runtime/sw1");
        assertThat(calls).containsExactly("runtime");
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
        OrchestratorProperties.Http http = new OrchestratorProperties.Http(Duration.ofSeconds(2), Duration.ofSeconds(5));
        return new OrchestratorProperties(new OrchestratorProperties.Orchestrator(
            "ph.control.orchestrator",
            "ph.control.orchestrator-status",
            metrics(),
            new OrchestratorProperties.Docker("/var/run/docker.sock", ComputeAdapterType.AUTO),
            new OrchestratorProperties.Images(null),
            new OrchestratorProperties.ScenarioManager(scenarioManagerUrl, http),
            new OrchestratorProperties.NetworkProxyManager("http://network-proxy-manager:8080", http)
        ));
    }

    private static OrchestratorProperties.Metrics metrics() {
        return new OrchestratorProperties.Metrics(
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
