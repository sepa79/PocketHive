package io.pockethive.scenarios.capabilities;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CapabilityImage(
        @JsonProperty(required = true) String name,
        String tag,
        String digest) {
}
