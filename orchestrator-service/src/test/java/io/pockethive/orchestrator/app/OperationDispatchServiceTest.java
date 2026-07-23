package io.pockethive.orchestrator.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.pockethive.orchestrator.domain.OperationCompletion;
import io.pockethive.orchestrator.domain.SwarmOperationCoordinator;
import io.pockethive.orchestrator.domain.SwarmStore;
import io.pockethive.swarm.model.lifecycle.OperationState;
import io.pockethive.swarm.model.lifecycle.OperationType;
import io.pockethive.swarm.model.lifecycle.Target;
import io.pockethive.swarm.model.lifecycle.TerminalResult;
import io.pockethive.swarm.model.lifecycle.TerminalStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OperationDispatchServiceTest {

  @Test
  void resultArrivingInsideTransportDispatchCannotRaceTheDispatchedTransition() {
    SwarmOperationCoordinator coordinator = new SwarmOperationCoordinator();
    OperationDispatchService service = new OperationDispatchService(
        coordinator, mock(OperationOutcomePublisher.class), new SwarmStore());
    Target target = new Target("swarm-controller", "controller-1");

    var reservation = service.dispatch(
        "alpha",
        OperationType.START,
        target,
        "correlation-1",
        "idempotency-1",
        Duration.ofSeconds(30),
        correlationId -> assertThat(coordinator.recordResult(
            "alpha",
            OperationType.START,
            target,
            correlationId,
            "idempotency-1",
            OperationState.SUCCEEDED,
            new TerminalResult(TerminalStatus.SUCCEEDED, false, Map.of()),
            Instant.now())).isEqualTo(OperationCompletion.COMPLETED));

    assertThat(reservation.operation().state()).isEqualTo(OperationState.SUCCEEDED);
    assertThat(reservation.operation().dispatchedAt()).isNotNull();
  }

  @Test
  void failedRemoveRecordsASchemaCompleteErrorResource() {
    SwarmOperationCoordinator coordinator = new SwarmOperationCoordinator();
    OperationOutcomePublisher outcomes = mock(OperationOutcomePublisher.class);
    SwarmStore swarms = new SwarmStore();
    swarms.register(new io.pockethive.orchestrator.domain.Swarm(
        "alpha", "controller-1", "manager-1", "run-1",
        io.pockethive.swarm.model.NetworkMode.DIRECT));
    OperationDispatchService service = new OperationDispatchService(coordinator, outcomes, swarms);
    Target target = new Target("swarm-controller", "controller-1");

    assertThatThrownBy(() -> service.dispatch(
        "alpha",
        OperationType.REMOVE,
        target,
        "correlation-remove",
        "idempotency-remove",
        Duration.ofSeconds(30),
        ignored -> { throw new IllegalStateException("remove failed"); }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("remove failed");

    ArgumentCaptor<io.pockethive.swarm.model.lifecycle.SwarmOperation> operation =
        ArgumentCaptor.forClass(io.pockethive.swarm.model.lifecycle.SwarmOperation.class);
    verify(outcomes).publish(operation.capture(), org.mockito.ArgumentMatchers.anyMap());
    @SuppressWarnings("unchecked")
    Map<String, Object> error = (Map<String, Object>) ((java.util.List<?>)
        operation.getValue().terminalResult().context().get("errors")).getFirst();
    assertThat(error)
        .containsEntry("code", "IllegalStateException")
        .containsEntry("message", "remove failed")
        .containsEntry("resource", null);
  }

  @Test
  void outcomePublicationFailureNeverMasksTheExecutionFailure() {
    SwarmOperationCoordinator coordinator = new SwarmOperationCoordinator();
    OperationOutcomePublisher outcomes = mock(OperationOutcomePublisher.class);
    SwarmStore swarms = new SwarmStore();
    swarms.register(new io.pockethive.orchestrator.domain.Swarm(
        "alpha", "controller-1", "manager-1", "run-1",
        io.pockethive.swarm.model.NetworkMode.DIRECT));
    OperationDispatchService service = new OperationDispatchService(coordinator, outcomes, swarms);
    Target target = new Target("swarm-controller", "controller-1");
    IllegalArgumentException executionFailure = new IllegalArgumentException("request denied");
    IllegalStateException publicationFailure = new IllegalStateException("outcome unavailable");
    when(outcomes.publish(any(), anyMap())).thenThrow(publicationFailure);

    Throwable thrown = catchThrowable(() -> service.dispatch(
        "alpha",
        OperationType.START,
        target,
        "correlation-start",
        "idempotency-start",
        Duration.ofSeconds(30),
        ignored -> { throw executionFailure; }));

    assertThat(thrown).isSameAs(executionFailure);
    assertThat(thrown.getSuppressed()).containsExactly(publicationFailure);
    assertThat(coordinator.findByCorrelation("correlation-start").orElseThrow().state())
        .isEqualTo(OperationState.FAILED);
  }
}
