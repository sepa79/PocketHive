package io.pockethive.networkproxy.infra.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.pockethive.auth.client.AuthServiceClient;
import io.pockethive.auth.client.AuthServiceServiceTokenProvider;
import io.pockethive.networkproxy.config.NetworkProxyManagerProperties;
import io.pockethive.swarm.model.NetworkProfile;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ScenarioManagerNetworkProfileClientTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void fetchUsesServicePrincipalBearerToken() throws Exception {
        AtomicInteger serviceLoginCalls = new AtomicInteger();
        AtomicReference<String> profileAuthorization = new AtomicReference<>();

        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/auth/service/login", exchange -> {
            serviceLoginCalls.incrementAndGet();
            respondJson(exchange, """
                {
                  "accessToken": "proxy-manager-token",
                  "tokenType": "Bearer",
                  "expiresAt": "2099-01-01T00:00:00Z",
                  "user": {
                    "id": "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                    "username": "network-proxy-manager",
                    "displayName": "Network Proxy Manager",
                    "active": true,
                    "authProvider": "DEV",
                    "grants": []
                  }
                }
                """);
        });
        server.createContext("/network-profiles/passthrough", exchange -> {
            profileAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respondJson(exchange, """
                {
                  "id": "passthrough",
                  "name": "Passthrough",
                  "faults": [],
                  "targets": ["payments"]
                }
                """);
        });
        server.start();

        URI baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        AuthServiceClient authServiceClient = new AuthServiceClient(baseUri, Duration.ofSeconds(2), Duration.ofSeconds(5));
        AuthServiceServiceTokenProvider tokenProvider = new AuthServiceServiceTokenProvider(
            authServiceClient,
            "network-proxy-manager",
            "network-proxy-manager-local-secret"
        );
        NetworkProxyManagerProperties properties = new NetworkProxyManagerProperties();
        properties.getScenarioManager().setUrl(baseUri.toString());

        ScenarioManagerNetworkProfileClient client = new ScenarioManagerNetworkProfileClient(
            new ObjectMapper(),
            properties,
            tokenProvider
        );

        NetworkProfile profile = client.fetch("passthrough");

        assertThat(profile.id()).isEqualTo("passthrough");
        assertThat(profileAuthorization.get()).isEqualTo("Bearer proxy-manager-token");
        assertThat(serviceLoginCalls).hasValue(1);
    }

    @Test
    void fetchRefreshesServiceTokenAfterUnauthorized() throws Exception {
        AtomicInteger serviceLoginCalls = new AtomicInteger();
        AtomicInteger profileCalls = new AtomicInteger();

        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/auth/service/login", exchange -> {
            int call = serviceLoginCalls.incrementAndGet();
            respondJson(exchange, """
                {
                  "accessToken": "%s",
                  "tokenType": "Bearer",
                  "expiresAt": "2099-01-01T00:00:00Z",
                  "user": {
                    "id": "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                    "username": "network-proxy-manager",
                    "displayName": "Network Proxy Manager",
                    "active": true,
                    "authProvider": "DEV",
                    "grants": []
                  }
                }
                """.formatted(call == 1 ? "stale-token" : "fresh-token"));
        });
        server.createContext("/network-profiles/passthrough", exchange -> {
            int call = profileCalls.incrementAndGet();
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
                  "id": "passthrough",
                  "name": "Passthrough",
                  "faults": [],
                  "targets": ["payments"]
                }
                """);
        });
        server.start();

        URI baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        AuthServiceClient authServiceClient = new AuthServiceClient(baseUri, Duration.ofSeconds(2), Duration.ofSeconds(5));
        AuthServiceServiceTokenProvider tokenProvider = new AuthServiceServiceTokenProvider(
            authServiceClient,
            "network-proxy-manager",
            "network-proxy-manager-local-secret"
        );
        NetworkProxyManagerProperties properties = new NetworkProxyManagerProperties();
        properties.getScenarioManager().setUrl(baseUri.toString());

        ScenarioManagerNetworkProfileClient client = new ScenarioManagerNetworkProfileClient(
            new ObjectMapper(),
            properties,
            tokenProvider
        );

        NetworkProfile profile = client.fetch("passthrough");

        assertThat(profile.id()).isEqualTo("passthrough");
        assertThat(serviceLoginCalls).hasValue(2);
        assertThat(profileCalls).hasValue(2);
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
