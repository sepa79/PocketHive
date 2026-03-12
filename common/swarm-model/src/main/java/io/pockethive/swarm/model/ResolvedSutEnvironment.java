package io.pockethive.swarm.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Collections;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ResolvedSutEnvironment(@NotBlank String sutId,
                                     @NotBlank String name,
                                     String type,
                                     @Valid Map<String, ResolvedSutEndpoint> endpoints) {

    public ResolvedSutEnvironment {
        sutId = requireText(sutId, "sutId");
        name = requireText(name, "name");
        type = trimToNull(type);
        endpoints = endpoints == null || endpoints.isEmpty()
            ? Map.of()
            : Map.copyOf(endpoints);
    }

    public Map<String, ResolvedSutEndpoint> endpoints() {
        return Collections.unmodifiableMap(endpoints);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
