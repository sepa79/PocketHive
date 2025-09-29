package io.pockethive.scenarios;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.pockethive.scenarios.assets.ScenarioAssets;
import io.pockethive.swarm.model.SwarmTemplate;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class Scenario {
    @NotBlank
    private String id;
    @NotBlank
    private String name;
    private String description;
    @Valid
    private SwarmTemplate template;
    @Valid
    private ScenarioAssets assets = new ScenarioAssets();
    private List<JsonNode> tracks = List.of();

    public Scenario() {}

    public Scenario(String id, String name, String description, SwarmTemplate template) {
        this(id, name, description, template, new ScenarioAssets(), List.of());
    }

    public Scenario(String id, String name, String description, SwarmTemplate template, ScenarioAssets assets, List<JsonNode> tracks) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.template = template;
        this.assets = assets == null ? new ScenarioAssets() : assets;
        this.tracks = tracks == null || tracks.isEmpty() ? List.of() : List.copyOf(tracks);
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

    public ScenarioAssets getAssets() {
        return assets;
    }

    public void setAssets(ScenarioAssets assets) {
        this.assets = assets == null ? new ScenarioAssets() : assets;
    }

    public List<JsonNode> getTracks() {
        return tracks;
    }

    public void setTracks(List<JsonNode> tracks) {
        this.tracks = tracks == null || tracks.isEmpty() ? List.of() : List.copyOf(tracks);
    }
}
