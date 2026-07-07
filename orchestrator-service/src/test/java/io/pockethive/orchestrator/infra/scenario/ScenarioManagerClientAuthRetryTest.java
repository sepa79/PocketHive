package io.pockethive.orchestrator.infra.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.pockethive.auth.client.AuthServiceClient;
import io.pockethive.auth.client.AuthServiceServiceTokenProvider;
import io.pockethive.manager.runtime.ComputeAdapterType;
import io.pockethive.observability.metrics.PocketHiveMetricsAdapter;
import io.pockethive.orchestrator.config.OrchestratorProperties;
import io.pockethive.sink.clickhouse.metrics.ClickHouseMetricsSinkProperties;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class ScenarioManagerClientAuthRetryTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void fetchScenarioTemplateRefreshesServiceTokenAfterUnauthorized() throws Exception {
        AtomicInteger serviceLoginCalls = new AtomicInteger();
        AtomicInteger templateCalls = new AtomicInteger();

        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/auth/service/login", exchange -> {
            int call = serviceLoginCalls.incrementAndGet();
            respondJson(exchange, """
                {
                  "accessToken": "%s",
                  "tokenType": "Bearer",
                  "expiresAt": "2099-01-01T00:00:00Z",
                  "user": {
                    "id": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                    "username": "orchestrator-service",
                    "displayName": "Orchestrator Service",
                    "active": true,
                    "authProvider": "DEV",
                    "grants": []
                  }
                }
                """.formatted(call == 1 ? "stale-token" : "fresh-token"));
        });
        server.createContext("/api/templates/local-rest", exchange -> {
            int call = templateCalls.incrementAndGet();
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (call == 1) {
                assertThat(authorization).isEqualTo("Bearer stale-token");
                exchange.sendResponseHeaders(401, -1);
                exchange.close();
                return;
            }
            assertThat(authorization).isEqualTo("Bearer fresh-token");
            respondJson(exchange, """
                {
                  "id": "local-rest",
                  "bundlePath": "e2e/local-rest",
                  "folderPath": "e2e",
                  "defunct": false
                }
                """);
        });
        server.start();

        URI baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        AuthServiceServiceTokenProvider provider = new AuthServiceServiceTokenProvider(
            new AuthServiceClient(baseUri, Duration.ofSeconds(2), Duration.ofSeconds(5)),
            "orchestrator-service",
            "orchestrator-secret"
        );
        ScenarioManagerClient client = new ScenarioManagerClient(
            new ObjectMapper(),
            properties(baseUri.toString()),
            new StaticListableBeanFactory(
                java.util.Map.of("orchestratorServiceTokenProvider", provider)
            ).getBeanProvider(AuthServiceServiceTokenProvider.class)
        );

        ScenarioManagerClient.ScenarioTemplateDescriptor descriptor = client.fetchScenarioTemplate("local-rest");

        assertThat(descriptor.id()).isEqualTo("local-rest");
        assertThat(serviceLoginCalls).hasValue(2);
        assertThat(templateCalls).hasValue(2);
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
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
