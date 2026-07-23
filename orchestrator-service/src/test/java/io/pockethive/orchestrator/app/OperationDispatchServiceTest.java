package io.pockethive.orchestrator.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

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
}
