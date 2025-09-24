package io.pockethive.control;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Confirmation emitted on failed command execution.
 */
public record ErrorConfirmation(
    Instant ts,
    String correlationId,
    String idempotencyKey,
    String signal,
    ConfirmationScope scope,
    String result,
    CommandState state,
    String phase,
    String code,
    String message,
    Boolean retryable,
    Map<String, Object> details
) implements Confirmation {

    public ErrorConfirmation {
        result = (result == null || result.isBlank()) ? "error" : result;
        if (details != null && !details.isEmpty()) {
            details = Collections.unmodifiableMap(new LinkedHashMap<>(details));
        } else {
            details = null;
        }
        scope = scope == null ? ConfirmationScope.EMPTY : scope;
        if (state != null && state.scope() == null && !scope.isEmpty()) {
            state = new CommandState(state.status(), scope, state.target(), state.enabled(), state.details());
        }
    }

    public ErrorConfirmation(Instant ts,
                             String correlationId,
                             String idempotencyKey,
                             String signal,
                             ConfirmationScope scope,
                             CommandState state,
                             String phase,
                             String code,
                             String message) {
        this(ts, correlationId, idempotencyKey, signal, scope, "error", state, phase, code, message, null, null);
    }

    public ErrorConfirmation(Instant ts,
                             String correlationId,
                             String idempotencyKey,
                             String signal,
                             ConfirmationScope scope,
                             CommandState state,
                             String phase,
                             String code,
                             String message,
                             Boolean retryable,
                             Map<String, Object> details) {
        this(ts, correlationId, idempotencyKey, signal, scope, "error", state, phase, code, message, retryable, details);
    }
}

