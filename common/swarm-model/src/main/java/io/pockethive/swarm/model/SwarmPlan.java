package io.pockethive.swarm.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SwarmPlan(String id,
                        @Valid List<Bee> bees,
                        @Valid Topology topology,
                        @Valid TrafficPolicy trafficPolicy,
                        String sutId,
                        @Valid SutEnvironment sutEnvironment) {
    public SwarmPlan {
        bees = bees == null ? List.of() : List.copyOf(bees);
    }

    public SwarmPlan(String id, List<Bee> bees) {
        this(id, bees, null, null, null, null);
    }

    public SwarmPlan(String id, List<Bee> bees, TrafficPolicy trafficPolicy) {
        this(id, bees, null, trafficPolicy, null, null);
    }

    public SwarmPlan(String id, List<Bee> bees, TrafficPolicy trafficPolicy, String sutId) {
        this(id, bees, null, trafficPolicy, sutId, null);
    }
}
