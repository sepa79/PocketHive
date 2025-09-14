package io.pockethive.controlplane;

import com.fasterxml.jackson.databind.JsonNode;
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
    Instant timestamp,
    JsonNode payload
) {
    public static ControlSignal forSwarm(String signal, String swarmId,
                                         String correlationId, String idempotencyKey) {
        return forSwarm(signal, swarmId, correlationId, idempotencyKey, null);
    }

    public static ControlSignal forSwarm(String signal, String swarmId,
                                         String correlationId, String idempotencyKey,
                                         JsonNode payload) {
        return new ControlSignal(signal, swarmId, null, null, correlationId, idempotencyKey,
            java.util.UUID.randomUUID().toString(), Instant.now(), payload);
    }

    public static ControlSignal forInstance(String signal, String swarmId,
                                            String role, String instance,
                                            String correlationId, String idempotencyKey) {
        return forInstance(signal, swarmId, role, instance, correlationId, idempotencyKey, null);
    }

    public static ControlSignal forInstance(String signal, String swarmId,
                                            String role, String instance,
                                            String correlationId, String idempotencyKey,
                                            JsonNode payload) {
        return new ControlSignal(signal, swarmId, role, instance, correlationId, idempotencyKey,
            java.util.UUID.randomUUID().toString(), Instant.now(), payload);
    }
}

