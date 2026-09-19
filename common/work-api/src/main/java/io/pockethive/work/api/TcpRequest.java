package io.pockethive.work.api;

import io.pockethive.worker.sdk.auth.AuthRef;
import java.util.List;
import java.util.Map;

/**
 * Responsibility: define the TcpRequest contract.
 * Must not: configure transport clients or own adapter lifecycle.
 * Contract: RESP-WORK-TCP-CONTRACT — docs/architecture/runtime-responsibilities.md#resp-work-tcp-contract.
 */
public record TcpRequest(
    String behavior,
    String body,
    Map<String, String> headers,
    String endTag,
    Integer maxBytes,
    List<AuthRef> authApplications
) {
    public TcpRequest {
        behavior = requireNonBlank(behavior, "behavior");
        body = body == null ? "" : body;
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        endTag = normalizeNullable(endTag);
        authApplications = authApplications == null ? List.of() : List.copyOf(authApplications);
        if (maxBytes != null && maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be > 0");
        }
    }

    public TcpRequest(String behavior, String body, Map<String, String> headers, String endTag, Integer maxBytes) {
        this(behavior, body, headers, endTag, maxBytes, List.of());
    }

    private static String requireNonBlank(String value, String field) {
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
