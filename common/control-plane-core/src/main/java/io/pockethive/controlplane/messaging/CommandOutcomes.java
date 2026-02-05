package io.pockethive.controlplane.messaging;

import io.pockethive.control.CommandOutcome;
import io.pockethive.control.CommandState;
import io.pockethive.control.ControlPlaneEnvelopeVersion;
import io.pockethive.control.ControlScope;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Canonical factories for control-plane command outcomes (kind=outcome).
 *
 * <p>All new outcome {@code data} fields must be introduced here and in
 * {@code docs/spec} rather than emitted ad-hoc by producers.</p>
 */
public final class CommandOutcomes {

    private static final Logger log = LoggerFactory.getLogger(CommandOutcomes.class);

    private CommandOutcomes() {
    }

    public static CommandOutcome success(String type,
                                         String origin,
                                         ControlScope scope,
                                         String correlationId,
                                         String idempotencyKey,
                                         Map<String, Object> runtime,
                                         String status,
                                         Boolean enabled,
                                         Map<String, Object> context,
                                         Instant timestamp) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", requireNonBlank("status", status));
        if (enabled != null) {
            data.put("enabled", enabled);
        }
        if (context != null && !context.isEmpty()) {
            data.put("context", immutableOrNull(context));
        }
        return outcome(type, origin, scope, correlationId, idempotencyKey, runtime, data, timestamp);
    }

    public static CommandOutcome failure(String type,
                                         String origin,
                                         ControlScope scope,
                                         String correlationId,
                                         String idempotencyKey,
                                         Map<String, Object> runtime,
                                         String status,
                                         Boolean retryable,
                                         Boolean enabled,
                                         Map<String, Object> context,
                                         Instant timestamp) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", requireNonBlank("status", status));
        if (retryable != null) {
            data.put("retryable", retryable);
        }
        if (enabled != null) {
            data.put("enabled", enabled);
        }
        if (context != null && !context.isEmpty()) {
            data.put("context", immutableOrNull(context));
        }
        return outcome(type, origin, scope, correlationId, idempotencyKey, runtime, data, timestamp);
    }

    public static CommandOutcome fromState(String type,
                                          String origin,
                                          ControlScope scope,
                                          String correlationId,
                                          String idempotencyKey,
                                          Map<String, Object> runtime,
                                          CommandState state,
                                          Boolean retryable,
                                          Map<String, Object> extraContext,
                                          Instant timestamp) {
        Objects.requireNonNull(state, "state");
        String status = requireNonBlank("state.status", state.status());
        Boolean enabled = "config-update".equals(type) ? state.enabled() : null;
        if ("config-update".equals(type) && enabled == null) {
            log.warn("config-update outcome missing enabled (origin={}, scope={}, correlationId={}, idempotencyKey={})",
                origin, scope, correlationId, idempotencyKey);
        }
        Map<String, Object> merged = mergeContext(state.details(), extraContext);
        if (retryable == null) {
            return success(type, origin, scope, correlationId, idempotencyKey, runtime, status, enabled, merged, timestamp);
        }
        if (Boolean.TRUE.equals(retryable) || Boolean.FALSE.equals(retryable)) {
            return failure(type, origin, scope, correlationId, idempotencyKey, runtime, status, retryable, enabled, merged, timestamp);
        }
        return success(type, origin, scope, correlationId, idempotencyKey, runtime, status, enabled, merged, timestamp);
    }

    private static CommandOutcome outcome(String type,
                                         String origin,
                                         ControlScope scope,
                                         String correlationId,
                                         String idempotencyKey,
                                         Map<String, Object> runtime,
                                         Map<String, Object> data,
                                         Instant timestamp) {
        Objects.requireNonNull(scope, "scope");
        Instant ts = timestamp != null ? timestamp : Instant.now();
        return new CommandOutcome(
            ts,
            ControlPlaneEnvelopeVersion.CURRENT,
            "outcome",
            requireNonBlank("type", type),
            requireNonBlank("origin", origin),
            scope,
            trimToNull(correlationId),
            trimToNull(idempotencyKey),
            runtime,
            data == null || data.isEmpty() ? null : Collections.unmodifiableMap(new LinkedHashMap<>(data))
        );
    }

    private static Map<String, Object> mergeContext(Map<String, Object> base, Map<String, Object> extra) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (base != null && !base.isEmpty()) {
            merged.putAll(base);
        }
        if (extra != null && !extra.isEmpty()) {
            merged.putAll(extra);
        }
        return merged.isEmpty() ? null : merged;
    }

    private static Map<String, Object> immutableOrNull(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return null;
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
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
