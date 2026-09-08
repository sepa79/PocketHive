package io.pockethive.work.api;

import java.util.Objects;

/**
 * Responsibility: define the Iso8583ResultEnvelope contract.
 * Must not: configure transport clients or own adapter lifecycle.
 * Contract: RESP-WORK-ISO-CONTRACT — docs/architecture/runtime-responsibilities.md#resp-work-iso-contract.
 */
public record Iso8583ResultEnvelope(
    String kind,
    Iso8583RequestInfo request,
    Iso8583Outcome outcome,
    Iso8583Metrics metrics
) {
    public static final String KIND = "iso8583.result";
    public static final String OUTCOME_ISO8583_RESPONSE = "iso8583_response";
    public static final String OUTCOME_TRANSPORT_ERROR = "transport_error";

    public Iso8583ResultEnvelope {
        if (!KIND.equals(kind)) {
            throw new IllegalArgumentException("Unsupported kind: " + kind);
        }
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(metrics, "metrics");
    }

    public static Iso8583ResultEnvelope of(Iso8583RequestInfo request, Iso8583Outcome outcome, Iso8583Metrics metrics) {
        return new Iso8583ResultEnvelope(KIND, request, outcome, metrics);
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
