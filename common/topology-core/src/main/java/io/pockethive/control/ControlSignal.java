package io.pockethive.control;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Canonical control-plane command signal envelope (kind=signal).
 * <p>
 * Carries the standard envelope metadata plus a per-command {@code data}
 * section. The {@code type} field is the canonical command identifier.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ControlSignal(
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

    public ControlSignal {
        Objects.requireNonNull(timestamp, "timestamp");
        version = requireNonBlank("version", version);
        kind = requireNonBlank("kind", kind);
        if (!"signal".equals(kind)) {
            throw new IllegalArgumentException("kind must be 'signal' for ControlSignal");
        }
        type = requireNonBlank("type", type);
        origin = requireNonBlank("origin", origin);
        scope = Objects.requireNonNull(scope, "scope");
        correlationId = requireNonBlank("correlationId", correlationId);
        idempotencyKey = trimToNull(idempotencyKey);
        if (data != null && !data.isEmpty()) {
            data = Collections.unmodifiableMap(new LinkedHashMap<>(data));
        } else {
            data = null;
        }
    }

    /**
     * Canonical factory for signals with explicit data payload.
     */
    public static ControlSignal signal(String type,
                                       String origin,
                                       ControlScope scope,
                                       String correlationId,
                                       String idempotencyKey,
                                       Map<String, Object> data) {
        return new ControlSignal(
            Instant.now(),
            ControlPlaneEnvelopeVersion.CURRENT,
            "signal",
            type,
            origin,
            Objects.requireNonNull(scope, "scope"),
            correlationId,
            idempotencyKey,
            data
        );
    }

    public static ControlSignal forSwarm(String type,
                                         String swarmId,
                                         String origin,
                                         String correlationId,
                                         String idempotencyKey,
                                         Map<String, Object> data) {
        return signal(type, origin, ControlScope.forSwarm(swarmId), correlationId, idempotencyKey, data);
    }

    public static ControlSignal forInstance(String type,
                                            String swarmId,
                                            String role,
                                            String instance,
                                            String origin,
                                            String correlationId,
                                            String idempotencyKey,
                                            Map<String, Object> data) {
        return signal(type, origin, ControlScope.forInstance(swarmId, role, instance), correlationId, idempotencyKey, data);
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
}
