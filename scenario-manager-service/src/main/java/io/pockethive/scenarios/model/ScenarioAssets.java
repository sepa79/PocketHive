package io.pockethive.scenarios.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ScenarioAssets {
    @Valid
    @NotEmpty
    private List<SystemUnderTest> suts;

    @Valid
    @NotEmpty
    private List<ScenarioDataset> datasets;

    @Valid
    @NotEmpty
    private List<SwarmTemplate> swarmTemplates;

    public ScenarioAssets() {
    }

    public List<SystemUnderTest> getSuts() {
        return suts;
    }

    public void setSuts(List<SystemUnderTest> suts) {
        this.suts = suts;
    }

    public List<ScenarioDataset> getDatasets() {
        return datasets;
    }

    public void setDatasets(List<ScenarioDataset> datasets) {
        this.datasets = datasets;
    }

    public List<SwarmTemplate> getSwarmTemplates() {
        return swarmTemplates;
    }

    public void setSwarmTemplates(List<SwarmTemplate> swarmTemplates) {
        this.swarmTemplates = swarmTemplates;
    }
}
