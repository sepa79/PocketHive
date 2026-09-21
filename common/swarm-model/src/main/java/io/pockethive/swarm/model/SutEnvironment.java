package io.pockethive.swarm.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared System Under Test (SUT) environment model used in control‑plane
 * contracts so orchestrator, swarm controller, and tools can agree on
 * the JSON shape without duplicating types.
 */
public record SutEnvironment(@NotBlank String id,
                             @NotBlank String name,
                             String type,
                             @Valid Map<String, SutEndpoint> endpoints) {

    public SutEnvironment {
        id = requireText(id, "id");
        name = requireText(name, "name");
        type = trimOptional(type, "type");
        if (endpoints == null) {
            throw new IllegalArgumentException("SUT environment endpoints must be provided");
        }
        if (endpoints.isEmpty()) {
            endpoints = Map.of();
        } else {
            Map<String, SutEndpoint> canonical = new LinkedHashMap<>();
            endpoints.forEach((endpointId, endpoint) -> {
                if (endpointId == null || endpointId.isBlank()) {
                    throw new IllegalArgumentException("SUT endpoint map key must not be blank");
                }
                if (!endpointId.equals(endpointId.trim())) {
                    throw new IllegalArgumentException(
                        "SUT endpoint map key must not have surrounding whitespace: '" + endpointId + "'");
                }
                if (endpoint == null) {
                    throw new IllegalArgumentException("SUT endpoint '" + endpointId + "' must not be null");
                }
                canonical.put(endpointId, endpoint);
            });
            endpoints = Map.copyOf(canonical);
        }
    }

    public Map<String, SutEndpoint> endpoints() {
        return Collections.unmodifiableMap(endpoints);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("SUT environment " + field + " must not be blank");
        }
        return value.trim();
    }

    private static String trimOptional(String value, String field) {
        if (value == null) {
            return null;
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException(
                "SUT environment " + field + " must not be blank when provided");
        }
        return value.trim();
    }
}
