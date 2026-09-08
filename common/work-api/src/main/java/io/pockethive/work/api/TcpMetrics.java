package io.pockethive.work.api;

/**
 * Responsibility: define the TcpMetrics contract.
 * Must not: configure transport clients or own adapter lifecycle.
 * Contract: RESP-WORK-TCP-CONTRACT — docs/architecture/runtime-responsibilities.md#resp-work-tcp-contract.
 */
public record TcpMetrics(
    long durationMs,
    long connectionLatencyMs
) {
    public TcpMetrics {
        if (durationMs < 0L) {
            throw new IllegalArgumentException("durationMs must be >= 0");
        }
        if (connectionLatencyMs < 0L) {
            throw new IllegalArgumentException("connectionLatencyMs must be >= 0");
        }
    }
}
