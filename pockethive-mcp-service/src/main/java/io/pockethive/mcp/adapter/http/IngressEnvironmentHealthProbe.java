package io.pockethive.mcp.adapter.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.mcp.application.EnvironmentHealthContract;
import io.pockethive.mcp.application.EnvironmentHealthProbePort;
import io.pockethive.mcp.application.EnvironmentHealthTarget;
import io.pockethive.mcp.config.EnvironmentHealthProperties;
import io.pockethive.mcp.config.PocketHiveMcpProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Responsibility: Probe configured PocketHive services through their canonical ingress health contracts.
 * Must not: Own domain state transitions or duplicate owner-service contracts.
 * Contract: docs/mcp/README.md.
 */

@Component
public final class IngressEnvironmentHealthProbe implements EnvironmentHealthProbePort {
    private static final String STATUS = "status";
    private static final String DATABASE = "database";
    private static final String PLAIN_OK = "ok";
    private static final String SPRING_UP = "UP";
    private static final String WIREMOCK_HEALTHY = "healthy";
    private static final String GRAFANA_DATABASE_OK = "ok";

    private final RestClient client;
    private final ObjectMapper mapper;

    @Autowired
    public IngressEnvironmentHealthProbe(RestClient.Builder builder,
                                         PocketHiveMcpProperties properties,
                                         EnvironmentHealthProperties healthProperties,
                                         ObjectMapper mapper) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(healthProperties.probeTimeout());
        requestFactory.setReadTimeout(healthProperties.probeTimeout());
        this.client = builder.baseUrl(properties.ownerApiBase().toString())
            .requestFactory(requestFactory)
            .build();
        this.mapper = mapper;
    }

    IngressEnvironmentHealthProbe(RestClient client, ObjectMapper mapper) {
        this.client = client;
        this.mapper = mapper;
    }

    @Override
    public boolean healthy(EnvironmentHealthTarget target) {
        try {
            ResponseEntity<String> response = client.get()
                .uri(target.probePath())
                .retrieve()
                .toEntity(String.class);
            return response.getStatusCode().is2xxSuccessful()
                && matches(target.contract(), response.getBody());
        } catch (RestClientException | JsonProcessingException exception) {
            return false;
        }
    }

    private boolean matches(EnvironmentHealthContract contract, String body) throws JsonProcessingException {
        if (body == null) {
            return false;
        }
        return switch (contract) {
            case PLAIN_OK -> PLAIN_OK.equals(body.trim());
            case SPRING_UP -> SPRING_UP.equals(json(body).path(STATUS).asText());
            case WIREMOCK_HEALTHY -> WIREMOCK_HEALTHY.equalsIgnoreCase(json(body).path(STATUS).asText());
            case GRAFANA_DATABASE_OK -> GRAFANA_DATABASE_OK.equalsIgnoreCase(json(body).path(DATABASE).asText());
        };
    }

    private JsonNode json(String body) throws JsonProcessingException {
        return mapper.readTree(body);
    }
}
