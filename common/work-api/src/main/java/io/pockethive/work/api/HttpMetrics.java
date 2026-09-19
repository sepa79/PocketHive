package io.pockethive.work.api;

/**
 * Responsibility: define the HttpMetrics contract.
 * Must not: configure transport clients or own adapter lifecycle.
 * Contract: RESP-WORK-HTTP-CONTRACT — docs/architecture/runtime-responsibilities.md#resp-work-http-contract.
 */
public record HttpMetrics(
    long durationMs,
    long connectionLatencyMs
) {
    public HttpMetrics {
        if (durationMs < 0L) {
            throw new IllegalArgumentException("durationMs must be >= 0");
        }
        if (connectionLatencyMs < 0L) {
            throw new IllegalArgumentException("connectionLatencyMs must be >= 0");
        }
    }
}
