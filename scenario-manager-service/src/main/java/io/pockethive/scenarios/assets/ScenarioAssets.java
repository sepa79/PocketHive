package io.pockethive.scenarios.assets;

import jakarta.validation.Valid;

import java.util.List;

public class ScenarioAssets {
    @Valid
    private List<SutAsset> suts = List.of();
    @Valid
    private List<DatasetAsset> datasets = List.of();
    @Valid
    private List<SwarmTemplateAsset> swarmTemplates = List.of();

    public ScenarioAssets() {
    }

    public ScenarioAssets(List<SutAsset> suts, List<DatasetAsset> datasets, List<SwarmTemplateAsset> swarmTemplates) {
        this.suts = copyOrEmpty(suts);
        this.datasets = copyOrEmpty(datasets);
        this.swarmTemplates = copyOrEmpty(swarmTemplates);
    }

    public List<SutAsset> getSuts() {
        return suts;
    }

    public void setSuts(List<SutAsset> suts) {
        this.suts = copyOrEmpty(suts);
    }

    public List<DatasetAsset> getDatasets() {
        return datasets;
    }

    public void setDatasets(List<DatasetAsset> datasets) {
        this.datasets = copyOrEmpty(datasets);
    }

    public List<SwarmTemplateAsset> getSwarmTemplates() {
        return swarmTemplates;
    }

    public void setSwarmTemplates(List<SwarmTemplateAsset> swarmTemplates) {
        this.swarmTemplates = copyOrEmpty(swarmTemplates);
    }

    private <T> List<T> copyOrEmpty(List<T> source) {
        return source == null || source.isEmpty() ? List.of() : List.copyOf(source);
    }
}
