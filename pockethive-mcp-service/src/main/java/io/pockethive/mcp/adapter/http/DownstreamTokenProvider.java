package io.pockethive.mcp.adapter.http;

import com.fasterxml.jackson.databind.JsonNode;
import io.pockethive.mcp.config.PocketHiveMcpProperties;
import java.time.Instant;
import java.time.Clock;
import java.time.DateTimeException;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class DownstreamTokenProvider {
    private final RestClient client;
    private final PocketHiveMcpProperties properties;
    private final Clock clock;
    private String token;
    private Instant expiresAt = Instant.EPOCH;

    public DownstreamTokenProvider(RestClient.Builder builder, PocketHiveMcpProperties properties, Clock clock) {
        this.client = builder.baseUrl(properties.ownerApiBase().toString()).build();
        this.properties = properties;
        this.clock = clock;
    }

    public synchronized String bearerToken() {
        Instant refreshBoundary = clock.instant().plusSeconds(30);
        if (token != null && refreshBoundary.isBefore(expiresAt)) {
            return token;
        }
        JsonNode response = client.post()
            .uri("/auth-service/api/auth/service/login")
            .body(Map.of(
                "serviceName", properties.downstreamServiceName(),
                "serviceSecret", properties.downstreamServiceSecret()))
            .retrieve()
            .body(JsonNode.class);
        if (response == null || response.path("accessToken").asText().isBlank()
            || response.path("expiresAt").asText().isBlank()) {
            throw new IllegalStateException("DOWNSTREAM_AUTH_RESPONSE_INVALID");
        }
        Instant receivedExpiry;
        try {
            receivedExpiry = Instant.parse(response.path("expiresAt").asText());
        } catch (DateTimeException exception) {
            throw new IllegalStateException("DOWNSTREAM_AUTH_RESPONSE_INVALID", exception);
        }
        if (!refreshBoundary.isBefore(receivedExpiry)) {
            throw new IllegalStateException("DOWNSTREAM_AUTH_RESPONSE_INVALID");
        }
        token = response.path("accessToken").asText();
        expiresAt = receivedExpiry;
        return token;
    }
}
