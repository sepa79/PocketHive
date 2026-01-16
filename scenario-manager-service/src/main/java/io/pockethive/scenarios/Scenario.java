package io.pockethive.scenarios;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.pockethive.swarm.model.SwarmTemplate;
import io.pockethive.swarm.model.Topology;
import io.pockethive.swarm.model.TrafficPolicy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Scenario {
    @NotBlank
    private String id;
    @NotBlank
    private String name;
    private String description;
    @Valid
    private SwarmTemplate template;
    @Valid
    private Topology topology;
    @Valid
    private TrafficPolicy trafficPolicy;
    /**
     * Optional scenario execution plan.
     * <p>
     * Scenario-manager treats this as an opaque JSON object; the orchestrator
     * is responsible for interpreting its structure. Keeping it as a
     * {@code Map} avoids a hard dependency on orchestrator-specific types
     * while still round-tripping the plan between YAML and JSON.
     */
    private Map<String, Object> plan;

    public Scenario() {}

    public Scenario(String id,
                    String name,
                    String description,
                    SwarmTemplate template,
                    Topology topology,
                    TrafficPolicy trafficPolicy,
                    Map<String, Object> plan) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.template = template;
        this.topology = topology;
        this.trafficPolicy = trafficPolicy;
        this.plan = plan;
    }

    public Scenario(String id,
                    String name,
                    String description,
                    SwarmTemplate template) {
        this(id, name, description, template, null, null, null);
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public SwarmTemplate getTemplate() {
        return template;
    }

    public void setTemplate(SwarmTemplate template) {
        this.template = template;
    }

    public Topology getTopology() {
        return topology;
    }

    public void setTopology(Topology topology) {
        this.topology = topology;
    }

    public TrafficPolicy getTrafficPolicy() {
        return trafficPolicy;
    }

    public void setTrafficPolicy(TrafficPolicy trafficPolicy) {
        this.trafficPolicy = trafficPolicy;
    }

    public Map<String, Object> getPlan() {
        return plan;
    }

    public void setPlan(Map<String, Object> plan) {
        this.plan = plan;
    }
}
