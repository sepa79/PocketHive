package io.pockethive.tcpmock.model;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MockState {
    private String scenarioName;
    private String currentState;
    private String nextState;
    private Map<String, Object> variables;
    private Instant lastUpdated;
    private int stepCount;

    public MockState() {
        this.variables = new ConcurrentHashMap<>();
        this.lastUpdated = Instant.now();
        this.stepCount = 0;
    }

    public MockState(String scenarioName, String initialState) {
        this();
        this.scenarioName = scenarioName;
        this.currentState = initialState;
    }

    public String getScenarioName() { return scenarioName; }
    public void setScenarioName(String scenarioName) { this.scenarioName = scenarioName; }

    public String getCurrentState() { return currentState; }
    public void setCurrentState(String currentState) {
        this.currentState = currentState;
        this.lastUpdated = Instant.now();
        this.stepCount++;
    }

    public String getNextState() { return nextState; }
    public void setNextState(String nextState) { this.nextState = nextState; }

    public Map<String, Object> getVariables() { return variables; }
    public void setVariable(String key, Object value) {
        this.variables.put(key, value);
        this.lastUpdated = Instant.now();
    }

    public Object getVariable(String key) { return variables.get(key); }

    public Instant getLastUpdated() { return lastUpdated; }
    public int getStepCount() { return stepCount; }

    public void advanceState() {
        if (nextState != null) {
            setCurrentState(nextState);
            this.nextState = null;
        }
    }

    public void reset() {
        this.currentState = null;
        this.nextState = null;
        this.variables.clear();
        this.stepCount = 0;
        this.lastUpdated = Instant.now();
    }
}
