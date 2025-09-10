package io.pockethive.orchestrator.domain;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry mapping Swarm controller container instances to their plans.
 */
public class SwarmPlanRegistry {
    private final Map<String, SwarmPlan> plans = new ConcurrentHashMap<>();

    public void register(String containerId, SwarmPlan plan) {
        plans.put(containerId, plan);
    }

    public Optional<SwarmPlan> remove(String containerId) {
        return Optional.ofNullable(plans.remove(containerId));
    }

    public Optional<SwarmPlan> find(String containerId) {
        return Optional.ofNullable(plans.get(containerId));
    }

    public void removeBySwarmId(String swarmId) {
        plans.entrySet().removeIf(e -> e.getValue().id().equals(swarmId));
    }
}

