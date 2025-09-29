package io.pockethive.scenarios.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ScenarioDataset {
    @NotBlank
    private String id;

    @NotBlank
    private String name;

    @Valid
    @NotNull
    private ScenarioDatasetProvider provider;

    @Valid
    private List<ScenarioDatasetBinding> bindings;

    public ScenarioDataset() {
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

    public ScenarioDatasetProvider getProvider() {
        return provider;
    }

    public void setProvider(ScenarioDatasetProvider provider) {
        this.provider = provider;
    }

    public List<ScenarioDatasetBinding> getBindings() {
        return bindings;
    }

    public void setBindings(List<ScenarioDatasetBinding> bindings) {
        this.bindings = bindings;
    }
}
