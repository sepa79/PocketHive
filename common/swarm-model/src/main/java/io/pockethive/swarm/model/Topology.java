package io.pockethive.swarm.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Topology(int version, @Valid List<TopologyEdge> edges) {
    public Topology {
        edges = edges == null ? List.of() : List.copyOf(edges);
    }
}
