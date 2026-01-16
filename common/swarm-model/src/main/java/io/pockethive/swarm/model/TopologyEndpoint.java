package io.pockethive.swarm.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TopologyEndpoint(@NotBlank String beeId, @NotBlank String port) {
    public TopologyEndpoint {
        if (beeId == null || beeId.isBlank()) {
            throw new IllegalArgumentException("topology endpoint beeId must not be blank");
        }
        if (port == null || port.isBlank()) {
            throw new IllegalArgumentException("topology endpoint port must not be blank");
        }
    }
}
