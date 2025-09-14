package io.pockethive.orchestrator.domain;

import java.time.Instant;

/**
 * Unified envelope for control-plane commands.
 */
public record ControlSignal(
    String signal,
    String swarmId,
    String role,
    String instance,
    String correlationId,
    String idempotencyKey,
    String messageId,
    Instant timestamp
) {
    public static ControlSignal forSwarm(String signal, String swarmId, String correlationId, String idempotencyKey) {
        return new ControlSignal(signal, swarmId, null, null, correlationId, idempotencyKey,
            java.util.UUID.randomUUID().toString(), Instant.now());
    }

    public static ControlSignal forInstance(String signal, String swarmId, String role, String instance,
                                            String correlationId, String idempotencyKey) {
        return new ControlSignal(signal, swarmId, role, instance, correlationId, idempotencyKey,
            java.util.UUID.randomUUID().toString(), Instant.now());
    }
}
