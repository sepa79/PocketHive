package io.pockethive.scenarios.capabilities;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CapabilityManifest(
        @JsonProperty(required = true) String schemaVersion,
        @JsonProperty(required = true) String capabilitiesVersion,
        @JsonProperty(required = true) CapabilityImage image,
        String role,
        List<CapabilityConfig> config,
        List<CapabilityAction> actions,
        List<CapabilityPanel> panels) {
}
