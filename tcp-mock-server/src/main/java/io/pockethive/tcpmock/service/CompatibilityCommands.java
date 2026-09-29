package io.pockethive.tcpmock.service;

import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Responsibility: coordinate existing compatibility reset and scenario operations.
 * Must not: store state or change persistence semantics.
 * Contract: RESP-TCP-MOCK-ADMIN — docs/architecture/runtime-responsibilities.md#resp-tcp-mock-admin.
 */
@Service
public class CompatibilityCommands {
    private final RequestStore requestStore;
    private final ScenarioManager scenarioManager;

    public CompatibilityCommands(RequestStore requestStore, ScenarioManager scenarioManager) {
        this.requestStore = requestStore;
        this.scenarioManager = scenarioManager;
    }

    public Map<String, String> reset() {
        requestStore.clearRequests();
        scenarioManager.resetAllScenarios();
        return Map.of("status", "Reset completed");
    }

    public Map<String, String> resetScenario(String name) {
        scenarioManager.setScenarioState(name, null);
        return Map.of("status", "Scenario reset", "scenario", name);
    }

    public Map<String, String> setScenarioState(String name, Map<String, String> body) {
        String state = body.get("state");
        scenarioManager.setScenarioState(name, state);
        return Map.of("status", "updated", "scenario", name, "state", state);
    }

    public Map<String, String> deleteScenario(String name) {
        scenarioManager.removeScenario(name);
        return Map.of("status", "deleted", "scenario", name);
    }
}
