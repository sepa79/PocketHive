package io.pockethive.controlplane.messaging;

import io.pockethive.control.AlertMessage;
import io.pockethive.control.ControlPlaneEnvelopeVersion;
import io.pockethive.control.ControlScope;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Canonical factories for control-plane alert envelopes (kind=event,type=alert).
 *
 * <p>All new alert shapes and alert codes must be introduced here and in
 * {@code docs/spec} rather than being emitted ad-hoc by producers.</p>
 */
public final class Alerts {

    private Alerts() {
    }

    public static final String TYPE = "alert";

    public static final class Codes {
        private Codes() {}

        public static final String RUNTIME_EXCEPTION = "runtime.exception";
        public static final String IO_OUT_OF_DATA = "io.out-of-data";
    }

    public static AlertMessage error(String origin,
                                     ControlScope scope,
                                     String correlationId,
                                     String idempotencyKey,
                                     Map<String, Object> runtime,
                                     String code,
                                     String message,
                                     String errorType,
                                     String errorDetail,
                                     String logRef,
                                     Map<String, Object> context,
                                     Instant timestamp) {
        Objects.requireNonNull(scope, "scope");
        Instant ts = timestamp != null ? timestamp : Instant.now();
        AlertMessage.AlertData data = new AlertMessage.AlertData(
            "error",
            requireNonBlank("code", code),
            requireNonBlank("message", message),
            trimToNull(errorType),
            trimToNull(errorDetail),
            trimToNull(logRef),
            immutableOrNull(context)
        );
        return new AlertMessage(
            ts,
            ControlPlaneEnvelopeVersion.CURRENT,
            "event",
            TYPE,
            requireNonBlank("origin", origin),
            scope,
            trimToNull(correlationId),
            trimToNull(idempotencyKey),
            runtime,
            data
        );
    }

    public static AlertMessage fromException(String origin,
                                             ControlScope scope,
                                             String correlationId,
                                             String idempotencyKey,
                                             Map<String, Object> runtime,
                                             String phase,
                                             Throwable exception,
                                             String logRef,
                                             Map<String, Object> context,
                                             Instant timestamp) {
        Objects.requireNonNull(exception, "exception");
        String errorType = exception.getClass().getName();
        String errorDetail = trimToNull(exception.getMessage());
        Map<String, Object> merged = new LinkedHashMap<>();
        if (phase != null && !phase.isBlank()) {
            merged.put("phase", phase.trim());
        }
        if (context != null && !context.isEmpty()) {
            merged.putAll(context);
        }
        String message = errorDetail != null ? errorDetail : errorType;
        return error(origin, scope, correlationId, idempotencyKey, runtime,
            Codes.RUNTIME_EXCEPTION,
            message,
            errorType,
            errorDetail,
            logRef,
            merged.isEmpty() ? null : merged,
            timestamp);
    }

    public static AlertMessage ioOutOfData(String origin,
                                           ControlScope scope,
                                           String correlationId,
                                           String idempotencyKey,
                                           Map<String, Object> runtime,
                                           String dataset,
                                           String message,
                                           String logRef,
                                           Map<String, Object> context,
                                           Instant timestamp) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (dataset != null && !dataset.isBlank()) {
            merged.put("dataset", dataset.trim());
        }
        if (context != null && !context.isEmpty()) {
            merged.putAll(context);
        }
        String msg = (message == null || message.isBlank())
            ? "Out of data"
            : message.trim();
        return error(origin, scope, correlationId, idempotencyKey, runtime,
            Codes.IO_OUT_OF_DATA,
            msg,
            null,
            null,
            logRef,
            merged.isEmpty() ? null : merged,
            timestamp);
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
