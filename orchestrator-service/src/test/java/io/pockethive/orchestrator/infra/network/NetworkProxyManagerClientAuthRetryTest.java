package io.pockethive.orchestrator.infra.network;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.pockethive.auth.client.AuthServiceClient;
import io.pockethive.auth.client.AuthServiceServiceTokenProvider;
import io.pockethive.manager.runtime.ComputeAdapterType;
import io.pockethive.orchestrator.config.OrchestratorProperties;
import io.pockethive.swarm.model.NetworkBinding;
import io.pockethive.swarm.model.NetworkBindingRequest;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class NetworkProxyManagerClientAuthRetryTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void bindSwarmRefreshesServiceTokenAfterUnauthorized() throws Exception {
        AtomicInteger serviceLoginCalls = new AtomicInteger();
        AtomicInteger bindCalls = new AtomicInteger();

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
        server.createContext("/api/network/bindings/swarm-1", exchange -> {
            int call = bindCalls.incrementAndGet();
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
                  "swarmId": "swarm-1",
                  "sutId": "sut-1",
                  "networkMode": "PROXIED",
                  "networkProfileId": "passthrough",
                  "effectiveMode": "PROXIED",
                  "requestedBy": "orchestrator-service",
                  "appliedAt": "2099-01-01T00:00:00Z",
                  "affectedEndpoints": []
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
        NetworkProxyManagerClient client = new NetworkProxyManagerClient(
            new ObjectMapper().findAndRegisterModules(),
            properties(baseUri.toString()),
            new StaticListableBeanFactory(
                Map.of("orchestratorServiceTokenProvider", provider)
            ).getBeanProvider(AuthServiceServiceTokenProvider.class)
        );

        NetworkBinding binding = client.bindSwarm(
            "swarm-1",
            new NetworkBindingRequest(
                "sut-1",
                io.pockethive.swarm.model.NetworkMode.PROXIED,
                "passthrough",
                "orchestrator-service",
                null,
                new io.pockethive.swarm.model.ResolvedSutEnvironment(
                    "sut-1",
                    "sut-1",
                    null,
                    Map.of()
                )
            ),
            "corr-1",
            "idem-1"
        );

        assertThat(binding.swarmId()).isEqualTo("swarm-1");
        assertThat(serviceLoginCalls).hasValue(2);
        assertThat(bindCalls).hasValue(2);
    }

    private static OrchestratorProperties properties(String networkProxyManagerUrl) {
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
            new OrchestratorProperties.ScenarioManager("http://scenario-manager:8080", http),
            new OrchestratorProperties.NetworkProxyManager(networkProxyManagerUrl, http)
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
