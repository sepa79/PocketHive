package io.pockethive.worker.sdk.api;

import io.pockethive.observability.ObservabilityContext;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record WorkItemEnvelope(
    String version,
    Map<String, Object> headers,
    String messageId,
    String contentType,
    List<WorkItemStepEnvelope> steps,
    ObservabilityContext observability
) {
    public WorkItemEnvelope {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(headers, "headers");
        Objects.requireNonNull(steps, "steps");
        Objects.requireNonNull(observability, "observability");
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("steps must not be empty");
        }
    }
}
