package io.pockethive.work.api;

import java.util.Objects;

/**
 * Responsibility: define the HttpResultEnvelope contract.
 * Must not: configure transport clients or own adapter lifecycle.
 * Contract: RESP-WORK-HTTP-CONTRACT — docs/architecture/runtime-responsibilities.md#resp-work-http-contract.
 */
public record HttpResultEnvelope(
    String kind,
    HttpRequestInfo request,
    HttpOutcome outcome,
    HttpMetrics metrics
) {
    public static final String KIND = "http.result";
    public static final String OUTCOME_HTTP_RESPONSE = "http_response";
    public static final String OUTCOME_TRANSPORT_ERROR = "transport_error";

    public HttpResultEnvelope {
        if (!KIND.equals(kind)) {
            throw new IllegalArgumentException("Unsupported kind: " + kind);
        }
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(metrics, "metrics");
    }

    public static HttpResultEnvelope of(HttpRequestInfo request, HttpOutcome outcome, HttpMetrics metrics) {
        return new HttpResultEnvelope(KIND, request, outcome, metrics);
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
