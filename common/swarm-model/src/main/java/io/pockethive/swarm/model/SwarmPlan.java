package io.pockethive.swarm.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SwarmPlan(String id,
                        @Valid List<Bee> bees,
                        @Valid TrafficPolicy trafficPolicy) {
    public SwarmPlan {
        bees = bees == null ? List.of() : List.copyOf(bees);
    }

    public SwarmPlan(String id, List<Bee> bees) {
        this(id, bees, null);
    }
}
