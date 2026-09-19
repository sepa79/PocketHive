package io.pockethive.work.api;

import io.pockethive.observability.ObservabilityContext;
import java.util.List;
import java.util.Map;

/**
 * Responsibility: define the WorkItemEnvelope contract.
 * Must not: configure transport clients or own adapter lifecycle.
 * Contract: RESP-WORK-WIRE — docs/architecture/runtime-responsibilities.md#resp-work-wire.
 */
public record WorkItemEnvelope(
    String version,
    Map<String, Object> headers,
    String messageId,
    String contentType,
    List<WorkItemStepEnvelope> steps,
    ObservabilityContext observability
) {}
