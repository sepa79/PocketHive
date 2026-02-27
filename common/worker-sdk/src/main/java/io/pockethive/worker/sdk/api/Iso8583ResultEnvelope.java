package io.pockethive.worker.sdk.api;

import java.util.Locale;
import java.util.Objects;

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

    public record Iso8583RequestInfo(
        String transport,
        String scheme,
        String method,
        String endpoint,
        String wireProfileId,
        String payloadAdapter,
        int payloadBytes
    ) {
        public Iso8583RequestInfo {
            transport = normalize(transport, "transport");
            scheme = normalizeNullable(scheme);
            method = normalize(method, "method").toUpperCase(Locale.ROOT);
            endpoint = normalizeNullable(endpoint);
            wireProfileId = normalize(wireProfileId, "wireProfileId");
            payloadAdapter = normalize(payloadAdapter, "payloadAdapter").toUpperCase(Locale.ROOT);
            if (payloadBytes < 0) {
                throw new IllegalArgumentException("payloadBytes must be >= 0");
            }
        }
    }

    public record Iso8583Outcome(
        String type,
        int status,
        String responseHex,
        String error
    ) {
        public Iso8583Outcome {
            type = normalize(type, "type");
            responseHex = normalizeNullable(responseHex);
            error = normalizeNullable(error);
        }
    }

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

    private static String normalize(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static String normalizeNullable(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
