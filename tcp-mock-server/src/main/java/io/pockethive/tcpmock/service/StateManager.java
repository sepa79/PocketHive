package io.pockethive.tcpmock.service;

import io.pockethive.tcpmock.model.MockState;
import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages scenario state for request matching.
 *
 * <p>Uses {@link ScenarioManager} as the single source of truth for scenario state strings
 * so the REST API, WireMock compat layer, and matching logic all see the same state.
 * A local cache of {@link MockState} objects holds per-scenario variables and step counts,
 * but the authoritative current-state string always comes from {@link ScenarioManager}.
 */
@Service
public class StateManager {

    private static final String INITIAL_STATE = "Started";

    /** Per-scenario metadata (variables, step count). State string is in ScenarioManager. */
    private final Map<String, MockState> metaCache = new ConcurrentHashMap<>();

    private final ScenarioManager scenarioManager;

    public StateManager(ScenarioManager scenarioManager) {
        this.scenarioManager = scenarioManager;
    }

    /**
     * Returns the MockState for the scenario, initialising it to {@code "Started"} if
     * this is the first access. Always in sync with ScenarioManager.
     */
    public MockState getOrCreateScenarioState(String scenarioName) {
        // Ensure ScenarioManager has an entry
        if (scenarioManager.getScenarioState(scenarioName) == null) {
            scenarioManager.setScenarioState(scenarioName, INITIAL_STATE);
        }
        MockState meta = metaCache.computeIfAbsent(scenarioName,
            name -> new MockState(name, INITIAL_STATE));
        // Keep meta in sync with the authoritative state string
        meta.setCurrentStateQuiet(scenarioManager.getScenarioState(scenarioName));
        return meta;
    }

    public boolean isInState(String scenarioName, String stateName) {
        String current = scenarioManager.getScenarioState(scenarioName);
        if (current == null) {
            // First access — initialise and check against "Started"
            scenarioManager.setScenarioState(scenarioName, INITIAL_STATE);
            return INITIAL_STATE.equals(stateName);
        }
        return stateName.equals(current);
    }

    public void updateScenarioState(String scenarioName, String newState) {
        scenarioManager.setScenarioState(scenarioName, newState);
        MockState meta = metaCache.get(scenarioName);
        if (meta != null) meta.setCurrentStateQuiet(newState);
    }

    public void setScenarioVariable(String scenarioName, String key, Object value) {
        metaCache.computeIfAbsent(scenarioName, n -> new MockState(n, INITIAL_STATE))
                 .setVariable(key, value);
    }

    public void resetScenario(String scenarioName) {
        scenarioManager.setScenarioState(scenarioName, INITIAL_STATE);
        MockState meta = metaCache.get(scenarioName);
        if (meta != null) meta.reset();
    }

    public void resetAllScenarios() {
        scenarioManager.resetAllScenarios();
        metaCache.values().forEach(MockState::reset);
    }

    public Map<String, MockState> getAllScenarios() {
        // Merge ScenarioManager entries into meta cache for a complete view
        scenarioManager.getAllScenarios().forEach((name, state) ->
            metaCache.computeIfAbsent(name, n -> new MockState(n, state))
                     .setCurrentStateQuiet(state));
        return new ConcurrentHashMap<>(metaCache);
    }

    public void removeScenario(String scenarioName) {
        scenarioManager.removeScenario(scenarioName);
        metaCache.remove(scenarioName);
    }

    public ScenarioManager getScenarioManager() {
        return scenarioManager;
    }
}
