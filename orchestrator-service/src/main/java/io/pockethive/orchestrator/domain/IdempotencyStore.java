package io.pockethive.orchestrator.domain;

import java.util.Optional;

/**
 * Tracks processed control commands to enforce idempotency.
 */
public interface IdempotencyStore {
    Optional<String> findCorrelation(String swarmId, String signal, String idempotencyKey);
    void record(String swarmId, String signal, String idempotencyKey, String correlationId);
    Optional<String> reserve(String swarmId, String signal, String idempotencyKey, String newCorrelationId);
    void rollback(String swarmId, String signal, String idempotencyKey, String correlationId);
}
