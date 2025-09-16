package io.pockethive.scenarios;

import io.pockethive.swarm.model.SwarmTemplate;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

public class Scenario {
    @NotBlank
    private String id;
    @NotBlank
    private String name;
    private String description;
    @Valid
    private SwarmTemplate template;

    public Scenario() {}

    public Scenario(String id, String name, String description, SwarmTemplate template) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.template = template;
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
}
