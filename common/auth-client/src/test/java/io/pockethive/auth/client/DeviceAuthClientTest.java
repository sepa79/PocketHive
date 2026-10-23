package io.pockethive.auth.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.pockethive.auth.contract.PocketHiveDeviceAuth;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DeviceAuthClientTest {
    private HttpServer server;
    private URI endpoint;
    private DeviceAuthClient client;
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<String> body = new AtomicReference<>("{}");
    private final AtomicReference<String> form = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/auth-service/", exchange -> {
            calls.incrementAndGet();
            form.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            if (status.get() == 302) exchange.getResponseHeaders().set("Location", endpoint + "/api/auth/me");
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/auth-service");
        client = new DeviceAuthClient(endpoint, PocketHiveDeviceAuth.DATA_CLIENT_ID, Duration.ofSeconds(1), Duration.ofSeconds(1), false);
    }

    @AfterEach void stop() { server.stop(0); }

    @Test void authorizationUsesTheRegisteredClientAndExactScope() {
        body.set("""
            {"device_code":"synthetic-secret","user_code":"ABCD-EFGH",
             "verification_uri":"http://localhost:8089/auth-service/oauth/device_verification",
             "verification_uri_complete":"http://localhost:8089/auth-service/oauth/device_verification?user_code=ABCD-EFGH",
             "expires_in":300}
            """);
        var authorization = client.start();
        assertThat(authorization.interval()).isNull();
        assertThat(authorization.toString()).doesNotContain("synthetic-secret");
        assertThat(form.get()).contains("client_id=pocketdata-device", "resource=urn%3Apockethive%3Aweb", "scope=pockethive%3Aweb");
    }

    @Test void pendingRemainsAnExplicitProtocolErrorWithoutRetry() {
        status.set(400); body.set("{\"error\":\"authorization_pending\",\"error_description\":\"untrusted text\"}");
        assertThatThrownBy(() -> client.poll("synthetic-code")).isInstanceOf(DeviceAuthException.class)
            .extracting("error").isEqualTo("authorization_pending");
        assertThat(calls.get()).isEqualTo(1);
        assertThat(form.get()).contains("device_code=synthetic-code", "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code");
    }

    @Test void deniesMalformedOrNonProtocolErrorBodies() {
        status.set(400); body.set("null");
        assertThatThrownBy(() -> client.poll("synthetic-code")).isInstanceOf(AuthServiceClientException.class)
            .hasMessage("PH device request unavailable");
        body.set("{\"error\":\"secret internal stacktrace\"}");
        assertThatThrownBy(() -> client.poll("synthetic-code")).hasMessage("PH device request unavailable");
    }

    @Test void tokenContractIsValidatedAndRedacted() {
        body.set("{\"access_token\":\"phweb_synthetic\",\"token_type\":\"Bearer\",\"expires_in\":300,\"scope\":\"pockethive:web\"}");
        assertThat(client.poll("synthetic-code").accessToken()).isEqualTo("phweb_synthetic");
        assertThat(client.poll("synthetic-code").toString()).doesNotContain("phweb_synthetic");
        body.set("{\"access_token\":\"machine_token\",\"token_type\":\"Bearer\",\"expires_in\":300,\"scope\":\"pockethive:web\"}");
        assertThatThrownBy(() -> client.poll("synthetic-code")).isInstanceOf(AuthServiceClientException.class);
    }

    @Test void revocationIsOneExplicitPost() {
        body.set("");
        client.revoke("phweb_synthetic");
        assertThat(form.get()).contains("client_id=pocketdata-device", "token=phweb_synthetic", "token_type_hint=access_token");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test void redirectsAreNeverFollowed() {
        status.set(302);
        assertThatThrownBy(client::start).isInstanceOf(AuthServiceClientException.class);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test void successAndErrorResponsesAreSizeBounded() {
        body.set(" ".repeat(1_048_577));
        assertThatThrownBy(client::start).isInstanceOf(AuthServiceClientException.class);
        status.set(400);
        assertThatThrownBy(client::start).isInstanceOf(AuthServiceClientException.class);
    }

    @Test void oversizedStreamingResponseIsCancelledInsteadOfDrained() {
        AtomicBoolean producing = new AtomicBoolean(true);
        server.createContext("/stream/oauth/device_authorization", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            try {
                exchange.getResponseBody().write(new byte[1_048_577]);
                exchange.getResponseBody().flush();
                while (producing.get()) {
                    exchange.getResponseBody().write(' ');
                    exchange.getResponseBody().flush();
                    try { Thread.sleep(25); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); break; }
                }
            } catch (java.io.IOException cancelled) {
                producing.set(false);
            } finally { exchange.close(); }
        });
        var streaming = new DeviceAuthClient(endpoint.resolve("/stream"), PocketHiveDeviceAuth.DATA_CLIENT_ID,
            Duration.ofSeconds(1), Duration.ofSeconds(1), false);
        try {
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                assertThatThrownBy(streaming::start).isInstanceOf(AuthServiceClientException.class));
        } finally { producing.set(false); }
    }

    @Test void identityTransportRejectsCoercedActiveFlags() {
        body.set("{\"id\":\"11111111-1111-1111-1111-111111111111\",\"username\":\"local-admin\",\"displayName\":\"Synthetic\",\"active\":\"true\",\"authProvider\":\"DEV\",\"grants\":[]}");
        var identity = new AuthServiceClient(endpoint, Duration.ofSeconds(1), Duration.ofSeconds(1));
        assertThatThrownBy(() -> identity.me("Bearer synthetic")).isInstanceOf(AuthServiceClientException.class);
    }

    @Test void configurationAcceptsOnlyThisClientsCanonicalLogout() {
        body.set("{\"logoutUri\":\"http://localhost:8089/auth-service/oauth/device/logout?client_id=pocketdata-device\"}");
        assertThat(client.configuration().logoutUri().getHost()).isEqualTo("localhost");
        body.set("{\"logoutUri\":\"http://localhost:8089/auth-service/oauth/device/logout?client_id=pocketplanner-device\"}");
        assertThatThrownBy(client::configuration).isInstanceOf(AuthServiceClientException.class);
    }

    @Test void remoteHttpDeviceEndpointsRequireExplicitConsumerPermission() {
        String issuer = "http://lab.example:8088/auth-service";
        body.set(authorization(issuer));
        assertThatThrownBy(client::start).isInstanceOf(AuthServiceClientException.class);
        var allowed = new DeviceAuthClient(endpoint, PocketHiveDeviceAuth.DATA_CLIENT_ID,
            Duration.ofSeconds(1), Duration.ofSeconds(1), true);
        assertThat(allowed.start().verificationUri().toString()).startsWith(issuer);

        body.set("{\"logoutUri\":\"" + issuer + PocketHiveDeviceAuth.LOGOUT_PATH
            + "?client_id=pocketdata-device\"}");
        assertThatThrownBy(client::configuration).isInstanceOf(AuthServiceClientException.class);
        assertThat(allowed.configuration().logoutUri().toString()).startsWith(issuer);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://ph.example.test/auth-service", "http://localhost:8088/auth-service",
        "http://127.0.0.1:8088/auth-service", "http://[::1]:8088/auth-service"})
    void secureAndLoopbackDeviceEndpointsNeedNoRemoteHttpPermission(String issuer) {
        body.set(authorization(issuer));
        assertThat(client.start().verificationUri().toString()).startsWith(issuer);
        body.set("{\"logoutUri\":\"" + issuer + PocketHiveDeviceAuth.LOGOUT_PATH
            + "?client_id=pocketdata-device\"}");
        assertThat(client.configuration().logoutUri().toString()).startsWith(issuer);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ftp://lab.example/auth-service", "http://user@lab.example/auth-service",
        "/auth-service", "http://lab.example/auth-service#fragment"})
    void remoteHttpPermissionNeverAdmitsUnsafeDeviceEndpoints(String issuer) {
        var allowed = new DeviceAuthClient(endpoint, PocketHiveDeviceAuth.DATA_CLIENT_ID,
            Duration.ofSeconds(1), Duration.ofSeconds(1), true);
        body.set(authorization(issuer));
        assertThatThrownBy(allowed::start).isInstanceOf(AuthServiceClientException.class);
        body.set("{\"logoutUri\":\"" + issuer + PocketHiveDeviceAuth.LOGOUT_PATH
            + "?client_id=pocketdata-device\"}");
        assertThatThrownBy(allowed::configuration).isInstanceOf(AuthServiceClientException.class);
    }

    private static String authorization(String issuer) {
        return """
            {"device_code":"synthetic-secret","user_code":"ABCD-EFGH",
             "verification_uri":"%s/oauth/device_verification",
             "verification_uri_complete":"%s/oauth/device_verification?user_code=ABCD-EFGH",
             "expires_in":300}
            """.formatted(issuer, issuer);
    }

    @Test void invalidInputsCannotReachTheNetwork() {
        assertThatThrownBy(() -> client.poll(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.revoke("machine_token")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DeviceAuthClient(endpoint, "pockethive-web", Duration.ofSeconds(1), Duration.ofSeconds(1), false))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(calls.get()).isZero();
    }
}
