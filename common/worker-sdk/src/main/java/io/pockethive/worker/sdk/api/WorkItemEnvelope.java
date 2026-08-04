package io.pockethive.worker.sdk.api;

import io.pockethive.observability.ObservabilityContext;
import java.util.List;
import java.util.Map;

public record WorkItemEnvelope(
    String version,
    Map<String, Object> headers,
    String messageId,
    String contentType,
    List<WorkItemStepEnvelope> steps,
    ObservabilityContext observability
) {}
