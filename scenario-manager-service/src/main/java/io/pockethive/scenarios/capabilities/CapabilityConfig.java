package io.pockethive.scenarios.capabilities;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CapabilityConfig(
        @JsonProperty(required = true) String name,
        @JsonProperty(required = true) String type,
        @JsonProperty("default") Object defaultValue,
        Integer min,
        Integer max,
        Boolean multiline,
        Map<String, Object> ui) {
}
