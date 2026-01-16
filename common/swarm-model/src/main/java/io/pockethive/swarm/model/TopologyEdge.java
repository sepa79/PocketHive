package io.pockethive.swarm.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TopologyEdge(@NotBlank String id,
                           @Valid TopologyEndpoint from,
                           @Valid TopologyEndpoint to,
                           TopologySelector selector) {
    public TopologyEdge {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("topology edge id must not be blank");
        }
        from = Objects.requireNonNull(from, "from");
        to = Objects.requireNonNull(to, "to");
    }
}
