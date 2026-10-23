package io.pockethive.mcp.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.pockethive.auth.client.AuthServiceClientException;
import io.pockethive.auth.client.AuthServiceServiceTokenProvider;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class McpOwnerAuthConfigurationTest {
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger redirectedCalls = new AtomicInteger();
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> responseBody = new AtomicReference<>(session("Synthetic service"));
    private HttpServer server;

    @BeforeEach
    void startFixture() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/auth-service/api/auth/service/login", exchange -> {
            calls.incrementAndGet();
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (status.get() == 307) exchange.getResponseHeaders().set("Location", "/capture");
            byte[] bytes = responseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
            finally { exchange.close(); }
        });
        server.createContext("/capture", exchange -> {
            redirectedCalls.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopFixture() { server.stop(0); }

    @Test
    void authenticatesWithConfiguredServiceCredentialsThroughTheSelectedIngress() throws Exception {
        assertThat(tokens().getAuthorizationHeader()).isEqualTo("Bearer phauth_synthetic");
        var request = new ObjectMapper().readTree(requestBody.get());
        assertThat(request.path("serviceName").asText()).isEqualTo("synthetic-mcp");
        assertThat(request.path("serviceSecret").asText()).isEqualTo("synthetic-secret");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void redirectsCannotForwardServiceCredentialsOrBecomeAToken() {
        status.set(307);
        responseBody.set("");
        assertThatThrownBy(() -> tokens().getAuthorizationHeader())
            .isInstanceOfSatisfying(AuthServiceClientException.class,
                failure -> assertThat(failure.getStatusCode()).isEqualTo(503));
        assertThat(calls.get()).isEqualTo(1);
        assertThat(redirectedCalls.get()).isZero();
    }

    @Test
    void oversizedValidSessionReplyCannotBecomeAToken() {
        responseBody.set(session("x".repeat(1_048_600)));
        assertThatThrownBy(() -> tokens().getAuthorizationHeader())
            .isInstanceOfSatisfying(AuthServiceClientException.class, failure -> {
                assertThat(failure.getStatusCode()).isEqualTo(503);
                assertThat(failure.getCause()).isNull();
                assertThat(failure.getMessage()).doesNotContain("phauth_synthetic", "synthetic-secret");
            });
        assertThat(calls.get()).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({"0,1", "-1,1", "1,0", "1,-1"})
    void nonPositiveAuthDeadlinesRejectClientConstructionBeforeAnyLogin(long connectSeconds, long readSeconds) {
        var deadlines = new McpOwnerAuthProperties(Duration.ofSeconds(connectSeconds), Duration.ofSeconds(readSeconds));
        assertThatThrownBy(() -> new McpOwnerAuthConfiguration().ownerAuthServiceClient(properties(), deadlines))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("Auth timeouts must be positive");
        assertThat(calls.get()).isZero();
    }

    private AuthServiceServiceTokenProvider tokens() {
        var configuration = new McpOwnerAuthConfiguration();
        var properties = properties();
        var deadlines = new McpOwnerAuthProperties(Duration.ofSeconds(1), Duration.ofSeconds(2));
        return configuration.ownerServiceTokenProvider(configuration.ownerAuthServiceClient(properties, deadlines), properties);
    }

    private PocketHiveMcpProperties properties() {
        PocketHiveMcpProperties properties = mock(PocketHiveMcpProperties.class);
        when(properties.ownerApiBase()).thenReturn(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
        when(properties.downstreamServiceName()).thenReturn("synthetic-mcp");
        when(properties.downstreamServiceSecret()).thenReturn("synthetic-secret");
        return properties;
    }

    private static String session(String displayName) {
        return """
            {"accessToken":"phauth_synthetic","tokenType":"Bearer","expiresAt":"2099-01-01T00:00:00Z",
             "user":{"id":"11111111-1111-1111-1111-111111111111","username":"synthetic-mcp",
             "displayName":"%s","active":true,"authProvider":"DEV","grants":[]}}
            """.formatted(displayName);
    }
}
