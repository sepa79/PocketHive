package io.pockethive.swarm.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import java.util.Locale;

@JsonIgnoreProperties(ignoreUnknown = true)
public record BeePort(@NotBlank String id, @NotBlank String direction) {
    public BeePort {
        id = normalizeId(id);
        direction = normalizeDirection(direction);
    }

    private static String normalizeId(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("port id must not be blank");
        }
        return value.trim();
    }

    private static String normalizeDirection(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("port direction must not be blank");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!"in".equals(normalized) && !"out".equals(normalized)) {
            throw new IllegalArgumentException("port direction must be 'in' or 'out'");
        }
        return normalized;
    }
}
