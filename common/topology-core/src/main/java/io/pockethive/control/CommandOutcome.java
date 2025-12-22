package io.pockethive.control;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Unified control-plane outcome envelope emitted for command results.
 * <p>
 * This record carries the canonical envelope meta fields plus a typed data
 * section whose shape is defined per ({@code kind}, {@code type}) in the
 * control-plane specs.
 */
public record CommandOutcome(
    Instant timestamp,
    String version,
    String kind,
    String type,
    String origin,
    ControlScope scope,
    String correlationId,
    String idempotencyKey,
    Map<String, Object> data
) {

    public CommandOutcome {
        Objects.requireNonNull(timestamp, "timestamp");
        version = requireNonBlank("version", version);
        kind = requireNonBlank("kind", kind);
        if (!"outcome".equals(kind)) {
            throw new IllegalArgumentException("kind must be 'outcome' for CommandOutcome");
        }
        type = requireNonBlank("type", type);
        origin = requireNonBlank("origin", origin);
        scope = Objects.requireNonNull(scope, "scope");
        correlationId = trimToNull(correlationId);
        idempotencyKey = trimToNull(idempotencyKey);
        if (data != null && !data.isEmpty()) {
            data = Collections.unmodifiableMap(new LinkedHashMap<>(data));
        } else {
            data = null;
        }
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

    /**
     * Convenience factory for simple successful outcomes where the data payload
     * is already fully prepared.
     */
    public static CommandOutcome success(String type,
                                         String origin,
                                         ControlScope scope,
                                         String correlationId,
                                         String idempotencyKey,
                                         Map<String, Object> data) {
        return new CommandOutcome(
            Instant.now(),
            ControlPlaneEnvelopeVersion.CURRENT,
            "outcome",
            type,
            origin,
            scope,
            correlationId,
            idempotencyKey,
            data
        );
    }
}
