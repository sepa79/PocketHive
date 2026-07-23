package io.pockethive.swarm.model.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SwarmOperationTest {

  @Test
  void terminalContextCanRepresentContractualNullValues() {
    TerminalResult result = new TerminalResult(
        TerminalStatus.SUCCEEDED, false, new java.util.LinkedHashMap<>(Map.of("target", "worker")));
    java.util.LinkedHashMap<String, Object> context = new java.util.LinkedHashMap<>(result.context());
    context.put("requestedEnabled", null);

    TerminalResult withNull = new TerminalResult(TerminalStatus.SUCCEEDED, false, context);

    assertTrue(withNull.context().containsKey("requestedEnabled"));
    assertNull(withNull.context().get("requestedEnabled"));
    assertThrows(UnsupportedOperationException.class, () -> withNull.context().put("x", true));
  }

  private static final Instant CREATED = Instant.parse("2026-07-22T12:00:00Z");
  private static final Instant DEADLINE = Instant.parse("2026-07-22T12:03:00Z");
  private static final Target TARGET = new Target("swarm-controller", "controller-1");

  @Test
  void acceptedOperationHasOneExplicitIdentityAndNoImplicitTerminalState() {
    SwarmOperation operation = SwarmOperation.accepted(
        "alpha", OperationType.START, TARGET, "correlation-1", "idempotency-1", CREATED, DEADLINE);

    assertEquals(OperationState.ACCEPTED, operation.state());
    assertNull(operation.dispatchedAt());
    assertNull(operation.completedAt());
    assertNull(operation.terminalResult());
    assertFalse(operation.terminal());
  }

  @Test
  void dispatchAndCompletionReturnNewValidatedSnapshots() {
    Instant dispatchedAt = CREATED.plusSeconds(1);
    Instant completedAt = CREATED.plusSeconds(5);
    TerminalResult result = new TerminalResult(
        TerminalStatus.SUCCEEDED,
        false,
        Map.of(
            "requestedWorkloadState", WorkloadIntent.RUNNING,
            "observedWorkloadState", WorkloadState.RUNNING));

    SwarmOperation accepted = SwarmOperation.accepted(
        "alpha", OperationType.START, TARGET, "correlation-1", "idempotency-1", CREATED, DEADLINE);
    SwarmOperation dispatched = accepted.dispatch(dispatchedAt);
    SwarmOperation completed = dispatched.complete(OperationState.SUCCEEDED, result, completedAt);

    assertEquals(OperationState.ACCEPTED, accepted.state());
    assertEquals(OperationState.DISPATCHED, dispatched.state());
    assertEquals(OperationState.SUCCEEDED, completed.state());
    assertTrue(completed.terminal());
    assertEquals(result, completed.terminalResult());
  }

  @Test
  void operationStateMustMatchTerminalWireStatus() {
    TerminalResult failed = new TerminalResult(TerminalStatus.FAILED, true, Map.of());
    SwarmOperation dispatched = SwarmOperation.accepted(
        "alpha", OperationType.START, TARGET, "correlation-1", "idempotency-1", CREATED, DEADLINE)
        .dispatch(CREATED.plusSeconds(1));

    IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> dispatched.complete(
        OperationState.SUCCEEDED, failed, CREATED.plusSeconds(2)));
    assertTrue(error.getMessage().contains("SUCCEEDED"));
    assertTrue(error.getMessage().contains("FAILED"));
  }

  @Test
  void terminalOperationCannotBeCompletedOrDispatchedAgain() {
    SwarmOperation terminal = SwarmOperation.accepted(
            "alpha", OperationType.STOP, TARGET, "correlation-2", "idempotency-2", CREATED, DEADLINE)
        .dispatch(CREATED.plusSeconds(1))
        .complete(
            OperationState.TIMED_OUT,
            new TerminalResult(TerminalStatus.TIMED_OUT, true, Map.of()),
            DEADLINE);

    assertThrows(IllegalStateException.class, () -> terminal.dispatch(DEADLINE.plusSeconds(1)));
    assertThrows(IllegalStateException.class, () -> terminal.complete(
        OperationState.FAILED,
        new TerminalResult(TerminalStatus.FAILED, true, Map.of()),
        DEADLINE.plusSeconds(1)));
  }

  @Test
  void coreIdentityNeverAcceptsBlankValues() {
    IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> SwarmOperation.accepted(
        " ", OperationType.START, TARGET, "correlation-1", "idempotency-1", CREATED, DEADLINE));
    assertTrue(error.getMessage().contains("swarmId"));
  }
}
