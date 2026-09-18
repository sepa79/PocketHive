package io.pockethive.worker.sdk.api;

import java.util.Map;

public record WorkItemStepEnvelope(
    int index,
    String payload,
    String payloadEncoding,
    Map<String, Object> headers
) {}
