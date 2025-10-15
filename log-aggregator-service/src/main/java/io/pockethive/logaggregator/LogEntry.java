package io.pockethive.logaggregator;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record LogEntry(
    String role,
    String traceId,
    String level,
    String message,
    @JsonProperty("@timestamp") String timestamp
) {}
