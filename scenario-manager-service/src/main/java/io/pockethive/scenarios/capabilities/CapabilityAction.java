package io.pockethive.scenarios.capabilities;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CapabilityAction(
        @JsonProperty(required = true) String id,
        @JsonProperty(required = true) String label,
        List<Map<String, Object>> params) {
}
