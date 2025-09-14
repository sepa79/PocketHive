package io.pockethive.orchestrator.domain;

import java.time.Instant;

/**
 * Confirmation emitted on failed command execution.
 */
public record ErrorConfirmation(
    String result,
    String signal,
    String swarmId,
    String role,
    String instance,
    String correlationId,
    String idempotencyKey,
    Instant timestamp,
    String code,
    String message
) implements Confirmation {
}
