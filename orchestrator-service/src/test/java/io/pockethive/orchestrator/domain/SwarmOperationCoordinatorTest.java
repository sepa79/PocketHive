package io.pockethive.orchestrator.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pockethive.swarm.model.lifecycle.OperationState;
import io.pockethive.swarm.model.lifecycle.OperationType;
import io.pockethive.swarm.model.lifecycle.SwarmOperation;
import io.pockethive.swarm.model.lifecycle.TerminalResult;
import io.pockethive.swarm.model.lifecycle.TerminalStatus;
import io.pockethive.swarm.model.lifecycle.Target;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SwarmOperationCoordinatorTest {

  private static final Instant NOW = Instant.parse("2026-07-22T12:00:00Z");
  private static final Instant DEADLINE = NOW.plusSeconds(180);
  private static final Target CONTROLLER = new Target("swarm-controller", "controller-1");

  @Test
  void duplicateLogicalRequestReturnsTheOriginalOperationIdentity() {
    SwarmOperationCoordinator coordinator = new SwarmOperationCoordinator();

    var first = coordinator.reserve(
        "alpha", OperationType.START, CONTROLLER, "correlation-1", "idempotency-1", NOW, DEADLINE);
    var duplicate = coordinator.reserve(
        "alpha", OperationType.START, CONTROLLER, "must-not-win", "idempotency-1", NOW.plusSeconds(1), DEADLINE);

    assertThat(first.reused()).isFalse();
    assertThat(duplicate.reused()).isTrue();
    assertThat(duplicate.operation().correlationId()).isEqualTo("correlation-1");
    assertThat(coordinator.operations()).hasSize(1);
  }

  @Test
  void sameConfigIdempotencyKeyForDifferentTargetsCreatesDistinctOperations() {
    SwarmOperationCoordinator coordinator = new SwarmOperationCoordinator();
    Target workerOne = new Target("generator", "generator-1");
    Target workerTwo = new Target("generator", "generator-2");

    var first = coordinator.reserve(
        "alpha", OperationType.CONFIG_UPDATE, workerOne,
        "correlation-1", "idempotency-1", NOW, DEADLINE);
    var second = coordinator.reserve(
        "alpha", OperationType.CONFIG_UPDATE, workerTwo,
        "correlation-2", "idempotency-1", NOW, DEADLINE);

    assertThat(first.reused()).isFalse();
    assertThat(second.reused()).isFalse();
    assertThat(coordinator.operations()).hasSize(2);
  }

  @Test
  void differentLifecycleCommandCannotOverwriteAnActiveOperation() {
    SwarmOperationCoordinator coordinator = new SwarmOperationCoordinator();
    coordinator.reserve(
        "alpha", OperationType.START, CONTROLLER, "correlation-1", "idempotency-1", NOW, DEADLINE);

    assertThatThrownBy(() -> coordinator.reserve(
        "alpha", OperationType.STOP, CONTROLLER, "correlation-2", "idempotency-2", NOW, DEADLINE))
        .isInstanceOf(OperationConflictException.class)
        .satisfies(error -> assertThat(((OperationConflictException) error).activeOperation().correlationId())
            .isEqualTo("correlation-1"));

    assertThat(coordinator.activeLifecycle("alpha"))
        .map(SwarmOperation::correlationId)
        .contains("correlation-1");
  }

  @Test
  void resultCompletesOnlyTheExactFourPartIdentity() {
    SwarmOperationCoordinator coordinator = new SwarmOperationCoordinator();
    coordinator.reserve(
        "alpha", OperationType.START, CONTROLLER, "correlation-1", "idempotency-1", NOW, DEADLINE);
    coordinator.markDispatched("correlation-1", NOW.plusSeconds(1));
    TerminalResult result = new TerminalResult(TerminalStatus.SUCCEEDED, false, Map.of());

    assertThat(coordinator.recordResult(
        "alpha", OperationType.STOP, CONTROLLER, "correlation-1", "idempotency-1",
        OperationState.SUCCEEDED, result, NOW.plusSeconds(2)))
        .isEqualTo(OperationCompletion.NO_MATCH);
    assertThat(coordinator.recordResult(
        "alpha", OperationType.START, CONTROLLER, "correlation-1", "wrong-key",
        OperationState.SUCCEEDED, result, NOW.plusSeconds(2)))
        .isEqualTo(OperationCompletion.NO_MATCH);
    assertThat(coordinator.recordResult(
        "alpha", OperationType.START, new Target("swarm-controller", "other-controller"),
        "correlation-1", "idempotency-1",
        OperationState.SUCCEEDED, result, NOW.plusSeconds(2)))
        .isEqualTo(OperationCompletion.NO_MATCH);
    assertThat(coordinator.recordResult(
        "alpha", OperationType.START, CONTROLLER, "correlation-1", "idempotency-1",
        OperationState.SUCCEEDED, result, NOW.plusSeconds(2)))
        .isEqualTo(OperationCompletion.COMPLETED);
  }

  @Test
  void lateResultAfterTimeoutCannotChangeTerminalState() {
    SwarmOperationCoordinator coordinator = new SwarmOperationCoordinator();
    coordinator.reserve(
        "alpha", OperationType.START, CONTROLLER, "correlation-1", "idempotency-1", NOW, DEADLINE);
    coordinator.markDispatched("correlation-1", NOW.plusSeconds(1));
    coordinator.recordResult(
        "alpha", OperationType.START, CONTROLLER, "correlation-1", "idempotency-1",
        OperationState.TIMED_OUT,
        new TerminalResult(TerminalStatus.TIMED_OUT, true, Map.of()),
        DEADLINE);

    assertThat(coordinator.recordResult(
        "alpha", OperationType.START, CONTROLLER, "correlation-1", "idempotency-1",
        OperationState.SUCCEEDED,
        new TerminalResult(TerminalStatus.SUCCEEDED, false, Map.of()),
        DEADLINE.plusSeconds(1)))
        .isEqualTo(OperationCompletion.ALREADY_TERMINAL);
    assertThat(coordinator.findByCorrelation("correlation-1"))
        .map(SwarmOperation::state)
        .contains(OperationState.TIMED_OUT);
  }

  @Test
  void expiryTerminatesEachDueOperationExactlyOnce() {
    SwarmOperationCoordinator coordinator = new SwarmOperationCoordinator();
    coordinator.reserve(
        "alpha", OperationType.STOP, CONTROLLER, "correlation-1", "idempotency-1", NOW, DEADLINE);
    coordinator.markDispatched("correlation-1", NOW.plusSeconds(1));

    assertThat(coordinator.expire(DEADLINE, operation ->
        new TerminalResult(TerminalStatus.TIMED_OUT, true, Map.of("operation", operation.type().name()))))
        .extracting(SwarmOperation::correlationId)
        .containsExactly("correlation-1");
    assertThat(coordinator.expire(DEADLINE.plusSeconds(1), operation ->
        new TerminalResult(TerminalStatus.TIMED_OUT, true, Map.of())))
        .isEmpty();
  }
}
