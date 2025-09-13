package io.pockethive.orchestrator.domain;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks pending swarm create operations so confirmations can echo ids.
 */
public class SwarmCreateTracker {
    public record Pending(String swarmId, String correlationId, String idempotencyKey) {}

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    public void register(String instanceId, Pending info) {
        pending.put(instanceId, info);
    }

    public Optional<Pending> remove(String instanceId) {
        return Optional.ofNullable(pending.remove(instanceId));
    }
}
