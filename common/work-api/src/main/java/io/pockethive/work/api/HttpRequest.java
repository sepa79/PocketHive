package io.pockethive.work.api;

import java.util.Locale;
import java.util.Map;

/**
 * Responsibility: define the HttpRequest contract.
 * Must not: configure transport clients or own adapter lifecycle.
 * Contract: RESP-WORK-HTTP-CONTRACT — docs/architecture/runtime-responsibilities.md#resp-work-http-contract.
 */
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
