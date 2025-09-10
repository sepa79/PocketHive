package io.pockethive.orchestrator.domain;

import java.util.List;

/**
 * Scenario description that can be expanded into a SwarmPlan.
 */
public record ScenarioPlan(SwarmTemplate template) {
    public SwarmPlan toSwarmPlan(String id) {
        List<SwarmPlan.Bee> bees = template.getBees();
        return new SwarmPlan(id, bees);
    }
}

