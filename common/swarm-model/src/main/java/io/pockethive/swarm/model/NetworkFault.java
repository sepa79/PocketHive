package io.pockethive.swarm.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import java.util.Collections;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record NetworkFault(@NotBlank String type,
                           Map<String, Object> config) {

    public NetworkFault {
        type = requireText(type, "type");
        config = config == null || config.isEmpty()
            ? Map.of()
            : Map.copyOf(config);
    }

    public Map<String, Object> config() {
        return Collections.unmodifiableMap(config);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
