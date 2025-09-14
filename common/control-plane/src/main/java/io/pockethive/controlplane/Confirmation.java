package io.pockethive.controlplane;

import java.time.Instant;

/**
 * Confirmation emitted on command completion.
 */
public record Confirmation(
    String result,
    String signal,
    String swarmId,
    String role,
    String instance,
    String correlationId,
    String idempotencyKey,
    Instant timestamp,
    String state,
    String code,
    String message,
    String notes
) {
    public static Confirmation success(String signal, String swarmId, String role, String instance,
                                       String correlationId, String idempotencyKey, String state, String notes) {
        return new Confirmation("success", signal, swarmId, role, instance, correlationId, idempotencyKey,
            Instant.now(), state, null, null, notes);
    }

    public static Confirmation error(String signal, String swarmId, String role, String instance,
                                     String correlationId, String idempotencyKey, String code, String message) {
        return new Confirmation("error", signal, swarmId, role, instance, correlationId, idempotencyKey,
            Instant.now(), null, code, message, null);
    }
}

