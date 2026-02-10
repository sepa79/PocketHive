package io.pockethive.worker.sdk.api;

import java.util.Map;
import java.util.Objects;

public record TcpRequestEnvelope(
    String kind,
    TcpRequest request
) {
    public static final String KIND = "tcp.request";

    public TcpRequestEnvelope {
        if (!KIND.equals(kind)) {
            throw new IllegalArgumentException("Unsupported kind: " + kind);
        }
        Objects.requireNonNull(request, "request");
    }

    public static TcpRequestEnvelope of(TcpRequest request) {
        return new TcpRequestEnvelope(KIND, request);
    }

    public record TcpRequest(
        String behavior,
        String body,
        Map<String, String> headers,
        String endTag,
        Integer maxBytes
    ) {
        public TcpRequest {
            behavior = requireNonBlank(behavior, "behavior");
            body = body == null ? "" : body;
            headers = headers == null ? Map.of() : Map.copyOf(headers);
            endTag = normalizeNullable(endTag);
            if (maxBytes != null && maxBytes <= 0) {
                throw new IllegalArgumentException("maxBytes must be > 0");
            }
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
}
