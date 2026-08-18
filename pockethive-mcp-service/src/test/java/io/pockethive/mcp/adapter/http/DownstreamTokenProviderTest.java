package io.pockethive.mcp.adapter.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.pockethive.mcp.config.PocketHiveMcpProperties;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class DownstreamTokenProviderTest {
    private static final Instant NOW = Instant.parse("2026-08-18T12:00:00Z");

    @Test
    void cachesOnlyAUsableServiceTokenAndRefreshesBeforeExpiry() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(NOW, NOW.plusSeconds(60), NOW.plusSeconds(91));
        DownstreamTokenProvider provider = new DownstreamTokenProvider(builder, properties(), clock);
        server.expect(requestTo("http://owner.internal:8088/auth-service/api/auth/service/login"))
            .andExpect(jsonPath("$.serviceName").value("pockethive-mcp"))
            .andExpect(jsonPath("$.serviceSecret").value("service-secret"))
            .andRespond(withSuccess("""
                {"accessToken":"service-token","expiresAt":"2026-08-18T12:02:00Z"}
                """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://owner.internal:8088/auth-service/api/auth/service/login"))
            .andRespond(withSuccess("""
                {"accessToken":"service-token","expiresAt":"2026-08-18T12:05:00Z"}
                """, MediaType.APPLICATION_JSON));

        assertThat(provider.bearerToken()).isEqualTo("service-token");
        assertThat(provider.bearerToken()).isEqualTo("service-token");
        assertThat(provider.bearerToken()).isEqualTo("service-token");
        server.verify();
    }

    @Test
    void rejectsMissingMalformedAndAlreadyExpiringAuthResponses() {
        assertInvalid("{}", NOW);
        assertInvalid("{\"accessToken\":\"token\",\"expiresAt\":\"not-an-instant\"}", NOW);
        assertInvalid("{\"accessToken\":\"token\",\"expiresAt\":\"2026-08-18T12:00:30Z\"}", NOW);
    }

    private static void assertInvalid(String response, Instant now) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://owner.internal:8088/auth-service/api/auth/service/login"))
            .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        DownstreamTokenProvider provider = new DownstreamTokenProvider(
            builder, properties(), Clock.fixed(now, java.time.ZoneOffset.UTC));

        assertThatThrownBy(provider::bearerToken)
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("DOWNSTREAM_AUTH_RESPONSE_INVALID");
        server.verify();
    }

    private static PocketHiveMcpProperties properties() {
        PocketHiveMcpProperties properties = mock(PocketHiveMcpProperties.class);
        when(properties.ownerApiBase()).thenReturn(URI.create("http://owner.internal:8088"));
        when(properties.downstreamServiceName()).thenReturn("pockethive-mcp");
        when(properties.downstreamServiceSecret()).thenReturn("service-secret");
        return properties;
    }
}
