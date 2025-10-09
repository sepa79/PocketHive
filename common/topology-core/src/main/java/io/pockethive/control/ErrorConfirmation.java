package io.pockethive.control;

import static io.pockethive.control.ConfirmationSupport.DEFAULT_ERROR_RESULT;
import static io.pockethive.control.ConfirmationSupport.defaultResult;
import static io.pockethive.control.ConfirmationSupport.defaultScope;
import static io.pockethive.control.ConfirmationSupport.immutableDetailsOrNull;

import java.time.Instant;
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
        result = defaultResult(result, DEFAULT_ERROR_RESULT);
        details = immutableDetailsOrNull(details);
        scope = defaultScope(scope);
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
    this(ts, correlationId, idempotencyKey, signal, scope, DEFAULT_ERROR_RESULT, state, phase, code, message, null, null);
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
        this(ts, correlationId, idempotencyKey, signal, scope, DEFAULT_ERROR_RESULT, state, phase, code, message, retryable, details);
    }
}

