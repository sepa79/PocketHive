package io.pockethive.worker.sdk.api;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

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

    public record HttpRequestInfo(
        String transport,
        String scheme,
        String method,
        String baseUrl,
        String path,
        String url
    ) {
        public HttpRequestInfo {
            transport = normalize(transport, "transport");
            scheme = normalizeNullable(scheme);
            method = normalize(method, "method").toUpperCase(Locale.ROOT);
            baseUrl = normalizeNullable(baseUrl);
            path = normalize(path, "path");
            url = normalizeNullable(url);
        }
    }

    public record HttpOutcome(
        String type,
        int status,
        Map<String, List<String>> headers,
        String body,
        String error
    ) {
        public HttpOutcome {
            type = normalize(type, "type");
            headers = headers == null ? Map.of() : Map.copyOf(headers);
            body = body == null ? "" : body;
            error = normalizeNullable(error);
        }
    }

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
