package io.pockethive.auth.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class AuthServiceServiceTokenProviderTest {
    @Test
    void reusesUnexpiredServiceToken() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AuthServiceClient client = new AuthServiceClient(builder.baseUrl("http://auth-service:8080").build());
        Instant now = Instant.parse("2026-04-17T12:00:00Z");

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
                      "expiresAt": "2026-04-17T13:00:00Z",
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

        AuthServiceServiceTokenProvider provider = new AuthServiceServiceTokenProvider(
            client,
            "orchestrator-service",
            "secret",
            Clock.fixed(now, ZoneOffset.UTC),
            Duration.ofMinutes(1)
        );

        assertThat(provider.getAuthorizationHeader()).isEqualTo("Bearer phauth_service_token");
        assertThat(provider.getAuthorizationHeader()).isEqualTo("Bearer phauth_service_token");
        server.verify();
    }
}
