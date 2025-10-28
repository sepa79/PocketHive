package io.pockethive.orchestrator.infra;

import io.pockethive.orchestrator.domain.IdempotencyStore;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple in-memory idempotency store.
 */
@Component
public class InMemoryIdempotencyStore implements IdempotencyStore {
    private final Map<Key, String> store = new ConcurrentHashMap<>();

    @Override
    public Optional<String> findCorrelation(String swarmId, String signal, String idempotencyKey) {
        return Optional.ofNullable(store.get(new Key(swarmId, signal, idempotencyKey)));
    }

    @Override
    public void record(String swarmId, String signal, String idempotencyKey, String correlationId) {
        store.putIfAbsent(new Key(swarmId, signal, idempotencyKey), correlationId);
    }

    @Override
    public Optional<String> reserve(String swarmId, String signal, String idempotencyKey, String newCorrelationId) {
        Key key = new Key(swarmId, signal, idempotencyKey);
        String correlation = store.computeIfAbsent(key, ignored -> newCorrelationId);
        if (correlation.equals(newCorrelationId)) {
            return Optional.empty();
        }
        return Optional.of(correlation);
    }

    @Override
    public void rollback(String swarmId, String signal, String idempotencyKey, String correlationId) {
        Key key = new Key(swarmId, signal, idempotencyKey);
        store.computeIfPresent(key, (ignored, existing) -> existing.equals(correlationId) ? null : existing);
    }

    private record Key(String swarmId, String signal, String idempotencyKey) {}
}
