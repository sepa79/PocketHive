package io.pockethive.scenarios.assets;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public class SwarmTemplateAsset {
    @NotBlank
    private String id;
    @NotBlank
    private String name;
    private String description;
    @NotBlank
    private String sutId;
    @NotBlank
    private String datasetId;
    @Min(1)
    private int swarmSize = 1;

    public SwarmTemplateAsset() {
    }

    public SwarmTemplateAsset(String id, String name, String description, String sutId, String datasetId, int swarmSize) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.sutId = sutId;
        this.datasetId = datasetId;
        this.swarmSize = swarmSize;
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

    public String getSutId() {
        return sutId;
    }

    public void setSutId(String sutId) {
        this.sutId = sutId;
    }

    public String getDatasetId() {
        return datasetId;
    }

    public void setDatasetId(String datasetId) {
        this.datasetId = datasetId;
    }

    public int getSwarmSize() {
        return swarmSize;
    }

    public void setSwarmSize(int swarmSize) {
        this.swarmSize = swarmSize;
    }
}
