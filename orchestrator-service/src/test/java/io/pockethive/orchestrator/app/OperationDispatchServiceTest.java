package io.pockethive.orchestrator.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.pockethive.control.CommandOutcome;
import io.pockethive.controlplane.codec.ControlPlaneCodec;
import io.pockethive.controlplane.messaging.ControlPlanePublisher;
import io.pockethive.controlplane.messaging.EventMessage;
import io.pockethive.orchestrator.domain.OperationCompletion;
import io.pockethive.orchestrator.domain.SwarmOperationCoordinator;
import io.pockethive.orchestrator.domain.SwarmStore;
import io.pockethive.swarm.model.lifecycle.OperationState;
import io.pockethive.swarm.model.lifecycle.OperationType;
import io.pockethive.swarm.model.lifecycle.RuntimeMetadata;
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
        new RuntimeMetadata("template-1", "run-1"),
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
        new RuntimeMetadata("template-1", "run-1"),
        ignored -> { throw new IllegalStateException("remove failed"); }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("remove failed");

    ArgumentCaptor<io.pockethive.swarm.model.lifecycle.SwarmOperation> operation =
        ArgumentCaptor.forClass(io.pockethive.swarm.model.lifecycle.SwarmOperation.class);
    verify(outcomes).publish(operation.capture());
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
    when(outcomes.publish(any())).thenThrow(publicationFailure);

    Throwable thrown = catchThrowable(() -> service.dispatch(
        "alpha",
        OperationType.START,
        target,
        "correlation-start",
        "idempotency-start",
        Duration.ofSeconds(30),
        new RuntimeMetadata("template-1", "run-1"),
        ignored -> { throw executionFailure; }));

    assertThat(thrown).isSameAs(executionFailure);
    assertThat(thrown.getSuppressed()).containsExactly(publicationFailure);
    assertThat(coordinator.findByCorrelation("correlation-start").orElseThrow().state())
        .isEqualTo(OperationState.FAILED);
  }

  @Test
  void createFailureBeforeRegistrationPublishesTheReservedRuntimeMetadata() throws Exception {
    SwarmOperationCoordinator coordinator = new SwarmOperationCoordinator();
    ControlPlanePublisher transport = mock(ControlPlanePublisher.class);
    OperationDispatchService service = new OperationDispatchService(
        coordinator, new OperationOutcomePublisher(transport, "orchestrator-1"), new SwarmStore());
    RuntimeMetadata runtime = new RuntimeMetadata("template-1", "planned-run-1");
    Target target = new Target("swarm-controller", "controller-1");

    assertThatThrownBy(() -> service.dispatch(
        "alpha",
        OperationType.CREATE,
        target,
        "correlation-create",
        "idempotency-create",
        Duration.ofSeconds(30),
        runtime,
        ignored -> {
          throw new IllegalStateException("manager launch failed");
        }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("manager launch failed");

    ArgumentCaptor<EventMessage> message = ArgumentCaptor.forClass(EventMessage.class);
    verify(transport).publishEvent(message.capture());
    CommandOutcome outcome = (CommandOutcome) message.getValue().payload();
    assertThat(outcome.runtime()).containsExactlyInAnyOrderEntriesOf(runtime.asControlPlaneRuntime());
    assertThat(coordinator.findByCorrelation("correlation-create").orElseThrow())
        .extracting(operation -> operation.runtime())
        .isEqualTo(runtime);
    assertThatCode(() -> ControlPlaneCodec.create().encode(outcome, message.getValue().routingKey()))
        .doesNotThrowAnyException();
  }
}
