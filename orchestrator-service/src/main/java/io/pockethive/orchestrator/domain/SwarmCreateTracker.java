package io.pockethive.orchestrator.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks pending swarm lifecycle operations so confirmations can echo ids and
 * timeouts can be enforced.
 */
public class SwarmCreateTracker {
    public enum Phase {
        CONTROLLER,
        TEMPLATE,
        START,
        STOP
    }

    public record Pending(String swarmId,
                          String instanceId,
                          String correlationId,
                          String idempotencyKey,
                          Phase phase,
                          Instant deadline) {

        public Pending next(Phase nextPhase, Instant nextDeadline) {
            return new Pending(swarmId, instanceId, correlationId, idempotencyKey, nextPhase, nextDeadline);
        }

        public boolean expired(Instant now) {
            return deadline != null && !deadline.isAfter(now);
        }
    }

    private final Map<String, Pending> controllers = new ConcurrentHashMap<>();
    private final Map<String, Pending> operations = new ConcurrentHashMap<>();

    private static String key(String swarmId, Phase phase) {
        return swarmId + "::" + phase.name();
    }

    public void register(String instanceId, Pending info) {
        if (info == null) return;
        controllers.put(instanceId, info);
    }

    public Optional<Pending> controllerPending(String swarmId) {
        if (swarmId == null || swarmId.isBlank()) {
            return Optional.empty();
        }
        return controllers.values().stream()
            .filter(pending -> pending != null && swarmId.equals(pending.swarmId()))
            .findFirst();
    }

    public Optional<Pending> remove(String instanceId) {
        return Optional.ofNullable(controllers.remove(instanceId));
    }

    public void expectTemplate(Pending base, Duration timeout) {
        if (base == null) return;
        operations.put(key(base.swarmId(), Phase.TEMPLATE),
            base.next(Phase.TEMPLATE, deadline(timeout)));
    }

    public void expectStart(String swarmId, String correlationId, String idempotencyKey, Duration timeout) {
        operations.put(key(swarmId, Phase.START),
            new Pending(swarmId, null, correlationId, idempotencyKey, Phase.START, deadline(timeout)));
    }

    public void expectStop(String swarmId, String correlationId, String idempotencyKey, Duration timeout) {
        operations.put(key(swarmId, Phase.STOP),
            new Pending(swarmId, null, correlationId, idempotencyKey, Phase.STOP, deadline(timeout)));
    }

    public Optional<Pending> complete(String swarmId, Phase phase) {
        return Optional.ofNullable(operations.remove(key(swarmId, phase)));
    }

    public List<Pending> expire(Instant now) {
        List<Pending> expired = new ArrayList<>();
        controllers.entrySet().removeIf(entry -> {
            Pending pending = entry.getValue();
            if (pending != null && pending.expired(now)) {
                expired.add(pending);
                return true;
            }
            return false;
        });
        operations.entrySet().removeIf(entry -> {
            Pending pending = entry.getValue();
            if (pending != null && pending.expired(now)) {
                expired.add(pending);
                return true;
            }
            return false;
        });
        return expired;
    }

    private Instant deadline(Duration timeout) {
        if (timeout == null) return null;
        return Instant.now().plus(timeout);
    }
}
