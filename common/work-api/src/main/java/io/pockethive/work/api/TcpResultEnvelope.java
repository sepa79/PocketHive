package io.pockethive.work.api;

import java.util.Objects;

/**
 * Responsibility: define the TcpResultEnvelope contract.
 * Must not: configure transport clients or own adapter lifecycle.
 * Contract: RESP-WORK-TCP-CONTRACT — docs/architecture/runtime-responsibilities.md#resp-work-tcp-contract.
 */
public record TcpResultEnvelope(
    String kind,
    TcpRequestInfo request,
    TcpOutcome outcome,
    TcpMetrics metrics
) {
    public static final String KIND = "tcp.result";
    public static final String OUTCOME_TCP_RESPONSE = "tcp_response";
    public static final String OUTCOME_TRANSPORT_ERROR = "transport_error";

    public TcpResultEnvelope {
        if (!KIND.equals(kind)) {
            throw new IllegalArgumentException("Unsupported kind: " + kind);
        }
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(metrics, "metrics");
    }

    public static TcpResultEnvelope of(TcpRequestInfo request, TcpOutcome outcome, TcpMetrics metrics) {
        return new TcpResultEnvelope(KIND, request, outcome, metrics);
    }

    static String normalize(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    static String normalizeNullable(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
