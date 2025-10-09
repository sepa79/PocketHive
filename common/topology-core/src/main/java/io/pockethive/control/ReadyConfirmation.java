package io.pockethive.control;

import static io.pockethive.control.ConfirmationSupport.DEFAULT_READY_RESULT;
import static io.pockethive.control.ConfirmationSupport.defaultResult;
import static io.pockethive.control.ConfirmationSupport.defaultScope;
import static io.pockethive.control.ConfirmationSupport.immutableDetailsOrNull;

import java.time.Instant;
import java.util.Map;

/**
 * Confirmation emitted on successful command execution.
 */
public record ReadyConfirmation(
    Instant ts,
    String correlationId,
    String idempotencyKey,
    String signal,
    ConfirmationScope scope,
    String result,
    CommandState state,
    Map<String, Object> details
) implements Confirmation {

    public ReadyConfirmation {
        result = defaultResult(result, DEFAULT_READY_RESULT);
        details = immutableDetailsOrNull(details);
        scope = defaultScope(scope);
    }

    public ReadyConfirmation(Instant ts,
                             String correlationId,
                             String idempotencyKey,
                             String signal,
                             ConfirmationScope scope,
                             CommandState state) {
        this(ts, correlationId, idempotencyKey, signal, scope, DEFAULT_READY_RESULT, state, null);
    }
}

