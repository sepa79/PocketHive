package io.pockethive.auth.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import io.pockethive.auth.contract.AuthProvider;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.test.web.client.MockRestServiceServer;

class AuthServiceClientTest {
    @Test
    void resolveCallsCanonicalEndpoint() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AuthServiceClient client = new AuthServiceClient(builder.baseUrl("http://auth-service:8080").build());

        server.expect(requestTo("http://auth-service:8080/api/auth/resolve"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
            .andRespond(withStatus(HttpStatus.OK)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                    {
                      "id": "11111111-1111-1111-1111-111111111111",
                      "username": "local-admin",
                      "displayName": "Local Admin",
                      "active": true,
                      "authProvider": "DEV",
                      "grants": []
                    }
                    """));

        assertThat(client.resolve("Bearer test-token").authProvider()).isEqualTo(AuthProvider.DEV);
        server.verify();
    }

    @Test
    void devLoginPostsExpectedPayload() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AuthServiceClient client = new AuthServiceClient(builder.baseUrl("http://auth-service:8080").build());

        server.expect(requestTo("http://auth-service:8080/api/auth/dev/login"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().json("""
                {"username":"local-admin"}
                """))
            .andRespond(withStatus(HttpStatus.OK)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                    {
                      "accessToken": "phauth_token",
                      "tokenType": "Bearer",
                      "expiresAt": "2026-04-17T16:10:00Z",
                      "user": {
                        "id": "11111111-1111-1111-1111-111111111111",
                        "username": "local-admin",
                        "displayName": "Local Admin",
                        "active": true,
                        "authProvider": "DEV",
                        "grants": []
                      }
                    }
                    """));

        assertThat(client.devLogin("local-admin").accessToken()).isEqualTo("phauth_token");
        server.verify();
    }

    @Test
    void serviceLoginPostsExpectedPayload() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AuthServiceClient client = new AuthServiceClient(builder.baseUrl("http://auth-service:8080").build());

        server.expect(requestTo("http://auth-service:8080/api/auth/service/login"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().json("""
                {"serviceName":"orchestrator-service","serviceSecret":"secret"}
                """))
            .andRespond(withStatus(HttpStatus.OK)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                    {
                      "accessToken": "phauth_service_token",
                      "tokenType": "Bearer",
                      "expiresAt": "2026-04-17T16:10:00Z",
                      "user": {
                        "id": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                        "username": "orchestrator-service",
                        "displayName": "Orchestrator Service",
                        "active": true,
                        "authProvider": "DEV",
                        "grants": []
                      }
                    }
                    """));

        assertThat(client.serviceLogin("orchestrator-service", "secret").accessToken()).isEqualTo("phauth_service_token");
        server.verify();
    }

    @Test
    void unauthorizedResponsesSurfaceStatusCode() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AuthServiceClient client = new AuthServiceClient(builder.baseUrl("http://auth-service:8080").build());

        server.expect(requestTo("http://auth-service:8080/api/auth/me"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.me("Bearer invalid"))
            .isInstanceOf(AuthServiceClientException.class)
            .extracting("statusCode")
            .isEqualTo(401);

        server.verify();
    }
}
