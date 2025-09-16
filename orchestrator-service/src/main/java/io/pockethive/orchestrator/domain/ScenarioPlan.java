package io.pockethive.orchestrator.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.pockethive.swarm.model.SwarmPlan;
import io.pockethive.swarm.model.SwarmTemplate;

/**
 * Scenario description that can be expanded into a SwarmPlan.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScenarioPlan(SwarmTemplate template) {
    public SwarmPlan toSwarmPlan(String id) {
        return new SwarmPlan(id, template.bees());
    }
}

