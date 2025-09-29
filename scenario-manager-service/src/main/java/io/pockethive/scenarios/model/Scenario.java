package io.pockethive.scenarios.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class Scenario {
    @NotBlank
    private String id;

    @NotBlank
    private String name;

    @NotBlank
    private String version;

    private String description;

    private Map<String, Object> metadata;

    @Valid
    @NotNull
    private ScenarioAssets assets;

    @Valid
    @NotNull
    private ScenarioDefinition scenario;

    public Scenario() {
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

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    public ScenarioAssets getAssets() {
        return assets;
    }

    public void setAssets(ScenarioAssets assets) {
        this.assets = assets;
    }

    public ScenarioDefinition getScenario() {
        return scenario;
    }

    public void setScenario(ScenarioDefinition scenario) {
        this.scenario = scenario;
    }
}
