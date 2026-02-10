package io.pockethive.worker.sdk.api;

import java.util.Locale;
import java.util.Objects;

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

    public record TcpRequestInfo(
        String transport,
        String scheme,
        String method,
        String configuredTarget,
        String endpoint
    ) {
        public TcpRequestInfo {
            transport = normalize(transport, "transport");
            scheme = normalizeNullable(scheme);
            method = normalize(method, "method").toUpperCase(Locale.ROOT);
            configuredTarget = normalizeNullable(configuredTarget);
            endpoint = normalizeNullable(endpoint);
        }
    }

    public record TcpOutcome(
        String type,
        int status,
        String body,
        String error
    ) {
        public TcpOutcome {
            type = normalize(type, "type");
            body = body == null ? "" : body;
            error = normalizeNullable(error);
        }
    }

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
