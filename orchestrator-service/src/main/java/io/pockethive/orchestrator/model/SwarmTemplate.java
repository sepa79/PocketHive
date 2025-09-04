package io.pockethive.orchestrator.model;

import java.util.List;
import java.util.Map;

public class SwarmTemplate {
    private String swarmId;
    private String swarmType; // REST, SOAP
    private List<String> components; // generator, moderator, processor, postprocessor
    private Map<String, String> environment;
    private int minInstances = 1;
    private int maxInstances = 5;
    private int targetTPS = 100;

    // Constructors
    public SwarmTemplate() {}

    public SwarmTemplate(String swarmId, String swarmType) {
        this.swarmId = swarmId;
        this.swarmType = swarmType;
        this.components = List.of("generator", "moderator", "processor", "postprocessor");
    }

    // Getters and setters
    public String getSwarmId() { return swarmId; }
    public void setSwarmId(String swarmId) { this.swarmId = swarmId; }

    public String getSwarmType() { return swarmType; }
    public void setSwarmType(String swarmType) { this.swarmType = swarmType; }

    public List<String> getComponents() { return components; }
    public void setComponents(List<String> components) { this.components = components; }

    public Map<String, String> getEnvironment() { return environment; }
    public void setEnvironment(Map<String, String> environment) { this.environment = environment; }

    public int getMinInstances() { return minInstances; }
    public void setMinInstances(int minInstances) { this.minInstances = minInstances; }

    public int getMaxInstances() { return maxInstances; }
    public void setMaxInstances(int maxInstances) { this.maxInstances = maxInstances; }

    public int getTargetTPS() { return targetTPS; }
    public void setTargetTPS(int targetTPS) { this.targetTPS = targetTPS; }
}