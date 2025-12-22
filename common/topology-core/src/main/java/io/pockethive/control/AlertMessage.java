package io.pockethive.control;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Canonical control-plane alert envelope (kind=event, type=alert).
 */
public record AlertMessage(
    Instant timestamp,
    String version,
    String kind,
    String type,
    String origin,
    ControlScope scope,
    String correlationId,
    String idempotencyKey,
    AlertData data
) {

    public AlertMessage {
        Objects.requireNonNull(timestamp, "timestamp");
        version = requireNonBlank("version", version);
        kind = requireNonBlank("kind", kind);
        if (!"event".equals(kind)) {
            throw new IllegalArgumentException("kind must be 'event' for AlertMessage");
        }
        type = requireNonBlank("type", type);
        if (!"alert".equals(type)) {
            throw new IllegalArgumentException("type must be 'alert' for AlertMessage");
        }
        origin = requireNonBlank("origin", origin);
        scope = Objects.requireNonNull(scope, "scope");
        correlationId = trimToNull(correlationId);
        idempotencyKey = trimToNull(idempotencyKey);
        data = Objects.requireNonNull(data, "data");
    }

    private static String requireNonBlank(String field, String value) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return trimmed;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AlertData(
        String level,
        String code,
        String message,
        String errorType,
        String errorDetail,
        String logRef,
        Map<String, Object> context
    ) {
        public AlertData {
            level = requireNonBlank("level", level);
            code = requireNonBlank("code", code);
            message = requireNonBlank("message", message);
            if (context != null && !context.isEmpty()) {
                context = Collections.unmodifiableMap(new LinkedHashMap<>(context));
            } else {
                context = null;
            }
        }
    }
}
