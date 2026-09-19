package io.pockethive.work.api;

import java.util.Map;

/**
 * Responsibility: define the WorkItemStepEnvelope contract.
 * Must not: configure transport clients or own adapter lifecycle.
 * Contract: RESP-WORK-WIRE — docs/architecture/runtime-responsibilities.md#resp-work-wire.
 */
public record WorkItemStepEnvelope(
    int index,
    String payload,
    String payloadEncoding,
    Map<String, Object> headers
) {}
