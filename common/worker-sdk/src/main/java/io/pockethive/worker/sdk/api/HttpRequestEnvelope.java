package io.pockethive.worker.sdk.api;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public record HttpRequestEnvelope(
    String kind,
    HttpRequest request
) {
    public static final String KIND = "http.request";

    public HttpRequestEnvelope {
        if (!KIND.equals(kind)) {
            throw new IllegalArgumentException("Unsupported kind: " + kind);
        }
        Objects.requireNonNull(request, "request");
    }

    public static HttpRequestEnvelope of(HttpRequest request) {
        return new HttpRequestEnvelope(KIND, request);
    }

    public record HttpRequest(
        String method,
        String path,
        Map<String, String> headers,
        Object body
    ) {
        public HttpRequest {
            method = normalizeMethod(method);
            path = requireNonBlank(path, "path");
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }

        private static String normalizeMethod(String method) {
            return requireNonBlank(method, "method").toUpperCase(Locale.ROOT);
        }

        private static String requireNonBlank(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + " must not be blank");
            }
            return value.trim();
        }
    }
}
