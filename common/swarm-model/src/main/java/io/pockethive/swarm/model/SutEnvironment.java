package io.pockethive.swarm.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Collections;
import java.util.Map;

/**
 * Shared System Under Test (SUT) environment model used in control‑plane
 * contracts so orchestrator, swarm controller, and tools can agree on
 * the JSON shape without duplicating types.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SutEnvironment(@NotBlank String id,
                             @NotBlank String name,
                             String type,
                             @Valid Map<String, SutEndpoint> endpoints) {

    public SutEnvironment {
        endpoints = endpoints == null || endpoints.isEmpty()
            ? Map.of()
            : Map.copyOf(endpoints);
    }

    public Map<String, SutEndpoint> endpoints() {
        return Collections.unmodifiableMap(endpoints);
    }
}

