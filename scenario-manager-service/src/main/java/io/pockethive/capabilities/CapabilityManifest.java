package io.pockethive.capabilities;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CapabilityManifest(
        String schemaVersion,
        String capabilitiesVersion,
        Image image,
        String role,
        List<ConfigEntry> config,
        List<Action> actions,
        List<Panel> panels,
        Ui ui
) {
    public CapabilityManifest {
        config = config == null ? List.of() : List.copyOf(config);
        actions = actions == null ? List.of() : List.copyOf(actions);
        panels = panels == null ? List.of() : List.copyOf(panels);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Image(String name, String tag, String digest) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ConfigEntry(
            String name,
            String type,
            @JsonProperty("default") JsonNode defaultValue,
            Number min,
            Number max,
            Boolean multiline,
            JsonNode when,
            JsonNode ui,
            JsonNode options
    ) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Action(
            String id,
            String label,
            List<ActionParameter> params
    ) {
        public Action {
            params = params == null ? List.of() : List.copyOf(params);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ActionParameter(
            String name,
            String type,
            @JsonProperty("default") JsonNode defaultValue,
            Boolean required,
            JsonNode ui
    ) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Panel(String id, JsonNode options) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Ui(String label, String color, String shape, String abbreviation, String ioType) { }
}
