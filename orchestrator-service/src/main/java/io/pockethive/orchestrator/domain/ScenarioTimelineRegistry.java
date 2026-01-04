package io.pockethive.orchestrator.domain;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry mapping swarm-controller instances to their scenario plans.
 * <p>
 * The orchestrator only needs to keep the plan long enough to hand it over to
 * the swarm-controller when its control plane reports {@code metric.status-full}.
 * After that the controller owns the plan and drives execution locally.
 */
public final class ScenarioTimelineRegistry {

    private final Map<String, String> plansByControllerInstance = new ConcurrentHashMap<>();

    public void register(String controllerInstanceId, String planJson) {
        if (controllerInstanceId == null || controllerInstanceId.isBlank()
            || planJson == null || planJson.isBlank()) {
            return;
        }
        plansByControllerInstance.put(controllerInstanceId, planJson);
    }

    public Optional<String> remove(String controllerInstanceId) {
        if (controllerInstanceId == null || controllerInstanceId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(plansByControllerInstance.remove(controllerInstanceId));
    }
}
