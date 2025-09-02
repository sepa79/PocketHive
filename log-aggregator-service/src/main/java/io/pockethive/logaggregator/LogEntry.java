package io.pockethive.logaggregator;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record LogEntry(String service, String traceId, String level, String message,
                       @JsonAlias("@timestamp") String timestamp) {}
