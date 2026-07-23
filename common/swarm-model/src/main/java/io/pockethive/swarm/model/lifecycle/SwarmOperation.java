package io.pockethive.swarm.model.lifecycle;

import java.time.Instant;
import java.util.Objects;

public record SwarmOperation(
    String swarmId,
    OperationType type,
    Target target,
    String correlationId,
    String idempotencyKey,
    OperationState state,
    Instant createdAt,
    Instant dispatchedAt,
    Instant deadlineAt,
    Instant completedAt,
    TerminalResult terminalResult
) {

  public SwarmOperation {
    swarmId = ContractValues.requireText("swarmId", swarmId);
    type = Objects.requireNonNull(type, "type");
    target = Objects.requireNonNull(target, "target");
    correlationId = ContractValues.requireText("correlationId", correlationId);
    idempotencyKey = ContractValues.requireText("idempotencyKey", idempotencyKey);
    state = Objects.requireNonNull(state, "state");
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
    deadlineAt = Objects.requireNonNull(deadlineAt, "deadlineAt");
    if (deadlineAt.isBefore(createdAt)) {
      throw new IllegalArgumentException("deadlineAt must not precede createdAt");
    }
    validateState(state, dispatchedAt, completedAt, terminalResult);
    if (dispatchedAt != null && dispatchedAt.isBefore(createdAt)) {
      throw new IllegalArgumentException("dispatchedAt must not precede createdAt");
    }
    if (completedAt != null && completedAt.isBefore(createdAt)) {
      throw new IllegalArgumentException("completedAt must not precede createdAt");
    }
  }

  public static SwarmOperation accepted(
      String swarmId,
      OperationType type,
      Target target,
      String correlationId,
      String idempotencyKey,
      Instant createdAt,
      Instant deadlineAt) {
    return new SwarmOperation(
        swarmId, type, target, correlationId, idempotencyKey, OperationState.ACCEPTED,
        createdAt, null, deadlineAt, null, null);
  }

  public SwarmOperation dispatch(Instant at) {
    if (state != OperationState.ACCEPTED) {
      throw new IllegalStateException("Only an ACCEPTED operation can be dispatched");
    }
    return new SwarmOperation(
        swarmId, type, target, correlationId, idempotencyKey, OperationState.DISPATCHED,
        createdAt, Objects.requireNonNull(at, "at"), deadlineAt, null, null);
  }

  public SwarmOperation complete(OperationState terminalState, TerminalResult result, Instant at) {
    if (terminal()) {
      throw new IllegalStateException("Terminal operation cannot be completed again");
    }
    if (!Objects.requireNonNull(terminalState, "terminalState").terminal()) {
      throw new IllegalArgumentException("Completion state must be terminal");
    }
    return new SwarmOperation(
        swarmId, type, target, correlationId, idempotencyKey, terminalState,
        createdAt, dispatchedAt, deadlineAt, Objects.requireNonNull(at, "at"),
        Objects.requireNonNull(result, "result"));
  }

  public boolean terminal() {
    return state.terminal();
  }

  private static void validateState(
      OperationState state,
      Instant dispatchedAt,
      Instant completedAt,
      TerminalResult terminalResult) {
    if (!state.terminal()) {
      if (completedAt != null || terminalResult != null) {
        throw new IllegalArgumentException("Non-terminal operation cannot have terminal evidence");
      }
      if (state == OperationState.ACCEPTED && dispatchedAt != null) {
        throw new IllegalArgumentException("ACCEPTED operation cannot have dispatchedAt");
      }
      if (state == OperationState.DISPATCHED && dispatchedAt == null) {
        throw new IllegalArgumentException("DISPATCHED operation requires dispatchedAt");
      }
      return;
    }
    if (completedAt == null || terminalResult == null) {
      throw new IllegalArgumentException("Terminal operation requires completion evidence");
    }
    TerminalStatus expected = switch (state) {
      case SUCCEEDED -> TerminalStatus.SUCCEEDED;
      case REJECTED -> TerminalStatus.REJECTED;
      case FAILED -> TerminalStatus.FAILED;
      case TIMED_OUT -> TerminalStatus.TIMED_OUT;
      case ACCEPTED, DISPATCHED -> throw new IllegalStateException("Not terminal: " + state);
    };
    if (terminalResult.status() != expected) {
      throw new IllegalArgumentException(
          "Operation state " + state + " does not match terminal status " + terminalResult.status());
    }
  }
}
