package io.pockethive.scenarios.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ScenarioTrack {
    @NotBlank
    private String name;

    @NotBlank
    private String templateRef;

    private String datasetRef;

    @Positive
    private Integer instances = 1;

    @Valid
    @NotEmpty
    private List<ScenarioTrackBlock> blocks;

    public ScenarioTrack() {
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getTemplateRef() {
        return templateRef;
    }

    public void setTemplateRef(String templateRef) {
        this.templateRef = templateRef;
    }

    public String getDatasetRef() {
        return datasetRef;
    }

    public void setDatasetRef(String datasetRef) {
        this.datasetRef = datasetRef;
    }

    public Integer getInstances() {
        return instances;
    }

    public void setInstances(Integer instances) {
        this.instances = instances == null ? 1 : instances;
    }

    public List<ScenarioTrackBlock> getBlocks() {
        return blocks;
    }

    public void setBlocks(List<ScenarioTrackBlock> blocks) {
        this.blocks = blocks;
    }
}
