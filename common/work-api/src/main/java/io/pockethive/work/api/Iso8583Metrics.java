package io.pockethive.work.api;

/**
 * Responsibility: define the Iso8583Metrics contract.
 * Must not: configure transport clients or own adapter lifecycle.
 * Contract: RESP-WORK-ISO-CONTRACT — docs/architecture/runtime-responsibilities.md#resp-work-iso-contract.
 */
public record Iso8583Metrics(
    long durationMs,
    long connectionLatencyMs
) {
    public Iso8583Metrics {
        if (durationMs < 0L) {
            throw new IllegalArgumentException("durationMs must be >= 0");
        }
        if (connectionLatencyMs < 0L) {
            throw new IllegalArgumentException("connectionLatencyMs must be >= 0");
        }
    }
}
