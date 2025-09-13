package io.pockethive.orchestrator.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Control signal payload sent to the control-plane.
 */
public record ControlSignal(
    String signal,
    String swarmId,
    String correlationId,
    String idempotencyKey,
    String messageId,
    Instant timestamp
) {
    public static ControlSignal forSwarm(String signal, String swarmId, String idempotencyKey, String correlationId) {
        return new ControlSignal(signal, swarmId, correlationId, idempotencyKey, UUID.randomUUID().toString(), Instant.now());
    }
}
