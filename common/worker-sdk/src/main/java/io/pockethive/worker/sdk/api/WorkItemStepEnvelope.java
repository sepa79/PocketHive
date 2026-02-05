package io.pockethive.worker.sdk.api;

import java.util.Map;
import java.util.Objects;

public record WorkItemStepEnvelope(
    int index,
    String payload,
    String payloadEncoding,
    Map<String, Object> headers
) {
    public WorkItemStepEnvelope {
        if (index < 0) {
            throw new IllegalArgumentException("index must be >= 0");
        }
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(payloadEncoding, "payloadEncoding");
        Objects.requireNonNull(headers, "headers");
    }
}
