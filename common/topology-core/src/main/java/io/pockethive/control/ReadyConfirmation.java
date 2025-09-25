package io.pockethive.control;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
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
        result = (result == null || result.isBlank()) ? "success" : result;
        if (details != null && !details.isEmpty()) {
            details = Collections.unmodifiableMap(new LinkedHashMap<>(details));
        } else {
            details = null;
        }
        scope = scope == null ? ConfirmationScope.EMPTY : scope;
    }

    public ReadyConfirmation(Instant ts,
                             String correlationId,
                             String idempotencyKey,
                             String signal,
                             ConfirmationScope scope,
                             CommandState state) {
        this(ts, correlationId, idempotencyKey, signal, scope, "success", state, null);
    }
}

