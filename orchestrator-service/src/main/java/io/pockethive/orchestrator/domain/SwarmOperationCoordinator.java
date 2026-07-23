package io.pockethive.orchestrator.domain;

import io.pockethive.swarm.model.lifecycle.OperationState;
import io.pockethive.swarm.model.lifecycle.OperationType;
import io.pockethive.swarm.model.lifecycle.SwarmOperation;
import io.pockethive.swarm.model.lifecycle.TerminalResult;
import io.pockethive.swarm.model.lifecycle.Target;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.List;
import java.util.function.Function;

/** In-process operation authority. Cross-restart recovery is intentionally outside this version. */
public final class SwarmOperationCoordinator {

  private final Map<OperationKey, String> correlationByRequest = new LinkedHashMap<>();
  private final Map<String, SwarmOperation> operationsByCorrelation = new LinkedHashMap<>();
  private final Map<String, ConfigUpdateExpectation> configExpectationsByCorrelation = new LinkedHashMap<>();

  public synchronized Reservation reserve(
      String swarmId,
      OperationType type,
      Target target,
      String correlationId,
      String idempotencyKey,
      Instant createdAt,
      Instant deadlineAt) {
    OperationKey key = new OperationKey(swarmId, type, target, idempotencyKey);
    String existingCorrelation = correlationByRequest.get(key);
    if (existingCorrelation != null) {
      return new Reservation(operationsByCorrelation.get(existingCorrelation), true);
    }
    if (type.lifecycle()) {
      activeLifecycle(swarmId).ifPresent(active -> {
        throw new OperationConflictException(active);
      });
    }
    if (operationsByCorrelation.containsKey(correlationId)) {
      throw new IllegalArgumentException("correlationId already exists: " + correlationId);
    }
    SwarmOperation operation = SwarmOperation.accepted(
        swarmId, type, target, correlationId, idempotencyKey, createdAt, deadlineAt);
    correlationByRequest.put(key, operation.correlationId());
    operationsByCorrelation.put(operation.correlationId(), operation);
    return new Reservation(operation, false);
  }

  public synchronized SwarmOperation markDispatched(String correlationId, Instant dispatchedAt) {
    SwarmOperation current = requireCorrelation(correlationId);
    SwarmOperation dispatched = current.dispatch(dispatchedAt);
    operationsByCorrelation.put(dispatched.correlationId(), dispatched);
    return dispatched;
  }

  public synchronized OperationCompletion recordResult(
      String swarmId,
      OperationType type,
      Target target,
      String correlationId,
      String idempotencyKey,
      OperationState terminalState,
      TerminalResult result,
      Instant completedAt) {
    SwarmOperation current = operationsByCorrelation.get(correlationId);
    if (current == null
        || !current.swarmId().equals(swarmId)
        || current.type() != type
        || !current.target().equals(target)
        || !current.idempotencyKey().equals(idempotencyKey)) {
      return OperationCompletion.NO_MATCH;
    }
    if (current.terminal()) {
      return OperationCompletion.ALREADY_TERMINAL;
    }
    SwarmOperation completed = current.complete(terminalState, result, completedAt);
    operationsByCorrelation.put(completed.correlationId(), completed);
    return OperationCompletion.COMPLETED;
  }

  public synchronized Optional<SwarmOperation> findByCorrelation(String correlationId) {
    return Optional.ofNullable(operationsByCorrelation.get(correlationId));
  }

  public synchronized void registerConfigExpectation(
      String correlationId, ConfigEnabledExpectation enabledExpectation) {
    SwarmOperation operation = requireCorrelation(correlationId);
    if (operation.type() != OperationType.CONFIG_UPDATE) {
      throw new IllegalArgumentException("Config expectation requires CONFIG_UPDATE operation");
    }
    ConfigUpdateExpectation next = new ConfigUpdateExpectation(enabledExpectation);
    ConfigUpdateExpectation existing = configExpectationsByCorrelation.putIfAbsent(correlationId, next);
    if (existing != null && !existing.equals(next)) {
      throw new IllegalStateException("Config expectation already registered with different values");
    }
  }

  public synchronized Optional<ConfigUpdateExpectation> configExpectation(String correlationId) {
    return Optional.ofNullable(configExpectationsByCorrelation.get(correlationId));
  }

  public synchronized Optional<SwarmOperation> find(
      String swarmId, OperationType type, Target target, String idempotencyKey) {
    String correlationId = correlationByRequest.get(new OperationKey(swarmId, type, target, idempotencyKey));
    return Optional.ofNullable(correlationId).map(operationsByCorrelation::get);
  }

  public synchronized Optional<SwarmOperation> activeLifecycle(String swarmId) {
    return operationsByCorrelation.values().stream()
        .filter(operation -> operation.swarmId().equals(swarmId))
        .filter(operation -> operation.type().lifecycle())
        .filter(operation -> !operation.terminal())
        .findFirst();
  }

  public synchronized Collection<SwarmOperation> operations() {
    return java.util.List.copyOf(operationsByCorrelation.values());
  }

  public synchronized List<SwarmOperation> expire(
      Instant now,
      Function<SwarmOperation, TerminalResult> timeoutResult) {
    Objects.requireNonNull(now, "now");
    Objects.requireNonNull(timeoutResult, "timeoutResult");
    java.util.ArrayList<SwarmOperation> expired = new java.util.ArrayList<>();
    for (SwarmOperation current : List.copyOf(operationsByCorrelation.values())) {
      if (current.terminal() || now.isBefore(current.deadlineAt())) {
        continue;
      }
      SwarmOperation terminal = current.complete(
          OperationState.TIMED_OUT,
          Objects.requireNonNull(timeoutResult.apply(current), "timeoutResult returned null"),
          now);
      operationsByCorrelation.put(terminal.correlationId(), terminal);
      expired.add(terminal);
    }
    return List.copyOf(expired);
  }

  private SwarmOperation requireCorrelation(String correlationId) {
    SwarmOperation operation = operationsByCorrelation.get(correlationId);
    if (operation == null) {
      throw new IllegalArgumentException("Unknown correlationId: " + correlationId);
    }
    return operation;
  }

  public record Reservation(SwarmOperation operation, boolean reused) {
    public Reservation {
      operation = Objects.requireNonNull(operation, "operation");
    }
  }

  public record ConfigUpdateExpectation(ConfigEnabledExpectation enabledExpectation) {
    public ConfigUpdateExpectation {
      enabledExpectation = Objects.requireNonNull(enabledExpectation, "enabledExpectation");
    }
  }

  public enum ConfigEnabledExpectation {
    UNCHANGED,
    ENABLED,
    DISABLED;

    public static ConfigEnabledExpectation fromRequested(Boolean requestedEnabled) {
      return requestedEnabled == null ? UNCHANGED : requestedEnabled ? ENABLED : DISABLED;
    }

    public boolean requiresObservation() {
      return this != UNCHANGED;
    }

    public boolean requestedEnabled() {
      if (!requiresObservation()) {
        throw new IllegalStateException("UNCHANGED has no requested enabled value");
      }
      return this == ENABLED;
    }
  }

  private record OperationKey(String swarmId, OperationType type, Target target, String idempotencyKey) {
    private OperationKey {
      if (swarmId == null || swarmId.isBlank()) {
        throw new IllegalArgumentException("swarmId must not be blank");
      }
      swarmId = swarmId.trim();
      type = Objects.requireNonNull(type, "type");
      target = Objects.requireNonNull(target, "target");
      if (idempotencyKey == null || idempotencyKey.isBlank()) {
        throw new IllegalArgumentException("idempotencyKey must not be blank");
      }
      idempotencyKey = idempotencyKey.trim();
    }
  }
}
