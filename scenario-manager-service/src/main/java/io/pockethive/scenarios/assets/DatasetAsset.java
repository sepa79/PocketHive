package io.pockethive.scenarios.assets;

import jakarta.validation.constraints.NotBlank;

public class DatasetAsset {
    @NotBlank
    private String id;
    @NotBlank
    private String name;
    private String description;
    @NotBlank
    private String uri;
    @NotBlank
    private String format;

    public DatasetAsset() {
    }

    public DatasetAsset(String id, String name, String description, String uri, String format) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.uri = uri;
        this.format = format;
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

    public String getUri() {
        return uri;
    }

    public void setUri(String uri) {
        this.uri = uri;
    }

    public String getFormat() {
        return format;
    }

    public void setFormat(String format) {
        this.format = format;
    }
}
