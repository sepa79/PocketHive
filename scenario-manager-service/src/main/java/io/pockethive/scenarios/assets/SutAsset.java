package io.pockethive.scenarios.assets;

import jakarta.validation.constraints.NotBlank;

public class SutAsset {
    @NotBlank
    private String id;
    @NotBlank
    private String name;
    private String description;
    @NotBlank
    private String entrypoint;
    @NotBlank
    private String version;

    public SutAsset() {
    }

    public SutAsset(String id, String name, String description, String entrypoint, String version) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.entrypoint = entrypoint;
        this.version = version;
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

    public String getEntrypoint() {
        return entrypoint;
    }

    public void setEntrypoint(String entrypoint) {
        this.entrypoint = entrypoint;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }
}
