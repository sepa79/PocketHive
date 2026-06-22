package io.pockethive.orchestrator.infra.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.pockethive.auth.client.AuthServiceServiceTokenProvider;
import io.pockethive.manager.runtime.ComputeAdapterType;
import io.pockethive.orchestrator.config.OrchestratorProperties;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
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
    void prepareScenarioRuntimeValidatesExistingBundleBeforeRuntimePreparation() throws Exception {
        List<String> calls = new CopyOnWriteArrayList<>();

        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/templates/local-rest", exchange -> {
            calls.add("template");
            respondJson(exchange, """
                {
                  "id": "local-rest",
                  "bundleKey": "e2e/local-rest",
                  "bundlePath": "e2e/local-rest",
                  "folderPath": "e2e",
                  "defunct": false
                }
                """);
        });
        server.createContext("/validation/scenario-bundles/existing", exchange -> {
            calls.add("validation");
            assertThat(exchange.getRequestURI().getRawQuery()).isEqualTo("bundleKey=e2e%2Flocal-rest");
            respondJson(exchange, """
                {
                  "ok": true,
                  "source": "scenario-manager",
                  "bundleKey": "e2e/local-rest",
                  "bundlePath": "e2e/local-rest",
                  "scenarioId": "local-rest",
                  "summary": { "errors": 0, "warnings": 0 },
                  "findings": []
                }
                """);
        });
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
        assertThat(calls).containsExactly("template", "validation", "runtime");
    }

    @Test
    void prepareScenarioRuntimeDoesNotCallRuntimePreparationWhenBundleValidationFails() throws Exception {
        AtomicInteger runtimeCalls = new AtomicInteger();

        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/templates/local-rest", exchange -> respondJson(exchange, """
            {
              "id": "local-rest",
              "bundleKey": "e2e/local-rest",
              "bundlePath": "e2e/local-rest",
              "folderPath": "e2e",
              "defunct": false
            }
            """));
        server.createContext("/validation/scenario-bundles/existing", exchange -> respondJson(exchange, """
            {
              "ok": false,
              "source": "scenario-manager",
              "bundleKey": "e2e/local-rest",
              "bundlePath": "e2e/local-rest",
              "scenarioId": "local-rest",
              "summary": { "errors": 1, "warnings": 0 },
              "findings": [
                {
                  "category": "scenario",
                  "code": "SCENARIO_DESCRIPTOR_INVALID",
                  "severity": "error",
                  "path": "scenario.yaml",
                  "message": "Invalid scenario descriptor.",
                  "fix": "Repair scenario.yaml."
                }
              ]
            }
            """));
        server.createContext("/scenarios/local-rest/runtime", exchange -> {
            runtimeCalls.incrementAndGet();
            respondJson(exchange, """
                {
                  "scenarioId": "local-rest",
                  "swarmId": "sw1",
                  "runtimeDir": "/tmp/runtime/sw1"
                }
                """);
        });
        server.start();

        assertThatThrownBy(() -> client().prepareScenarioRuntime("local-rest", "sw1"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Scenario bundle validation failed")
            .hasMessageContaining("SCENARIO_DESCRIPTOR_INVALID")
            .hasMessageContaining("scenario.yaml");
        assertThat(runtimeCalls).hasValue(0);
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
            new OrchestratorProperties.Rabbit("ph.logs", new OrchestratorProperties.Logging(false)),
            new OrchestratorProperties.Metrics(new OrchestratorProperties.Pushgateway(
                false,
                "http://pushgateway:9091",
                Duration.ofSeconds(10),
                "DELETE",
                "PocketHive",
                new OrchestratorProperties.GroupingKey("instance")
            )),
            new OrchestratorProperties.Docker("/var/run/docker.sock", ComputeAdapterType.AUTO),
            new OrchestratorProperties.Images(null),
            new OrchestratorProperties.ScenarioManager(scenarioManagerUrl, http),
            new OrchestratorProperties.NetworkProxyManager("http://network-proxy-manager:8080", http)
        ));
    }

    private static void respondJson(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
