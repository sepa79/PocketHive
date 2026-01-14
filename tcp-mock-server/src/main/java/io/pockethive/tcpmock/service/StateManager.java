package io.pockethive.tcpmock.service;

import io.pockethive.tcpmock.model.MockState;
import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class StateManager {

    private final Map<String, MockState> scenarios = new ConcurrentHashMap<>();
    private final Map<String, MockState> clientStates = new ConcurrentHashMap<>();
    private final ScenarioManager scenarioManager;

    public StateManager(ScenarioManager scenarioManager) {
        this.scenarioManager = scenarioManager;
    }

    public MockState getOrCreateScenarioState(String scenarioName) {
        return scenarios.computeIfAbsent(scenarioName, name -> new MockState(name, "Started"));
    }

    public MockState getOrCreateClientState(String clientAddress, String scenarioName) {
        String key = clientAddress + ":" + scenarioName;
        return clientStates.computeIfAbsent(key, k -> new MockState(scenarioName, "Started"));
    }

    public void updateScenarioState(String scenarioName, String newState) {
        MockState state = getOrCreateScenarioState(scenarioName);
        state.setCurrentState(newState);
    }

    public void setScenarioVariable(String scenarioName, String key, Object value) {
        MockState state = getOrCreateScenarioState(scenarioName);
        state.setVariable(key, value);
    }

    public void advanceScenarioState(String scenarioName) {
        MockState state = scenarios.get(scenarioName);
        if (state != null) {
            state.advanceState();
        }
    }

    public void resetScenario(String scenarioName) {
        MockState state = scenarios.get(scenarioName);
        if (state != null) {
            state.reset();
        }
    }

    public void resetAllScenarios() {
        scenarios.values().forEach(MockState::reset);
    }

    public Map<String, MockState> getAllScenarios() {
        return new ConcurrentHashMap<>(scenarios);
    }

    public boolean isInState(String scenarioName, String stateName) {
        MockState state = scenarios.get(scenarioName);
        return state != null && stateName.equals(state.getCurrentState());
    }

    public void removeScenario(String scenarioName) {
        scenarios.remove(scenarioName);
        // Remove all client states for this scenario
        clientStates.entrySet().removeIf(entry ->
            entry.getValue().getScenarioName().equals(scenarioName));
    }

    public ScenarioManager getScenarioManager() {
        return scenarioManager;
    }
}
