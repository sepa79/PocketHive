package io.pockethive.worker.sdk.api;

import java.util.Map;
import java.util.Objects;

/**
 * Snapshot of a single logical processing step within a {@link WorkItem}'s history.
 * <p>
 * Steps are ordered from earliest ({@link #index()} == 0) to latest.
 */
public final class WorkStep {

    private final int index;
    private final String payload;
    private final WorkPayloadEncoding payloadEncoding;
    private final Map<String, Object> headers;

    public WorkStep(int index, String payload, WorkPayloadEncoding payloadEncoding, Map<String, Object> headers) {
        if (index < 0) {
            throw new IllegalArgumentException("index must be >= 0");
        }
        this.index = index;
        this.payload = Objects.requireNonNull(payload, "payload");
        this.payloadEncoding = Objects.requireNonNull(payloadEncoding, "payloadEncoding");
        this.headers = Map.copyOf(Objects.requireNonNull(headers, "headers"));
    }

    public int index() {
        return index;
    }

    /**
     * Textual payload snapshot for this step.
     */
    public String payload() {
        return payload;
    }

    public WorkPayloadEncoding payloadEncoding() {
        return payloadEncoding;
    }

    /**
     * Headers that were in effect for this step.
     */
    public Map<String, Object> headers() {
        return headers;
    }

    public WorkStep withIndex(int newIndex) {
        return new WorkStep(newIndex, payload, payloadEncoding, headers);
    }

    public WorkStep withHeaders(Map<String, Object> newHeaders) {
        return new WorkStep(index, payload, payloadEncoding, newHeaders);
    }

    public WorkStep withPayload(String newPayload, WorkPayloadEncoding newEncoding) {
        return new WorkStep(index, newPayload, newEncoding, headers);
    }
}
