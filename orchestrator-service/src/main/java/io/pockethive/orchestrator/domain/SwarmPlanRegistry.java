package io.pockethive.orchestrator.domain;

import io.pockethive.swarm.model.SwarmPlan;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry mapping Swarm controller instances to their plans.
 */
public class SwarmPlanRegistry {
    private final Map<String, SwarmPlan> plans = new ConcurrentHashMap<>();

    public void register(String instanceId, SwarmPlan plan) {
        plans.put(instanceId, plan);
    }

    public Optional<SwarmPlan> remove(String instanceId) {
        return Optional.ofNullable(plans.remove(instanceId));
    }

    public Optional<SwarmPlan> find(String instanceId) {
        return Optional.ofNullable(plans.get(instanceId));
    }

    public void removeBySwarmId(String swarmId) {
        plans.entrySet().removeIf(e -> e.getValue().id().equals(swarmId));
    }
}

