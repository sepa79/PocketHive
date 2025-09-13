package io.pockethive.orchestrator.domain;

import java.time.Instant;

/**
 * Confirmation emitted on successful command execution.
 */
public record ReadyConfirmation(
    String result,
    String signal,
    String swarmId,
    String role,
    String instance,
    String correlationId,
    String idempotencyKey,
    Instant timestamp,
    String state,
    String notes
) implements Confirmation {
}
