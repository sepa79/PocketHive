package io.pockethive.orchestrator.app;

import io.pockethive.swarm.model.lifecycle.ControlRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.controlplane.ControlPlaneSignals;
import io.pockethive.controlplane.messaging.ControlPlanePublisher;
import io.pockethive.controlplane.spring.ControlPlaneProperties;
import io.pockethive.orchestrator.auth.OrchestratorAuthorization;
import io.pockethive.orchestrator.domain.HiveJournal;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmOperationCoordinator;
import io.pockethive.orchestrator.domain.SwarmStateStore;
import io.pockethive.orchestrator.domain.SwarmStore;
import io.pockethive.orchestrator.domain.SwarmTemplateMetadata;
import io.pockethive.orchestrator.runtime.FilesystemSwarmStartupArtifactStore;
import io.pockethive.swarm.model.lifecycle.OperationType;
import io.pockethive.swarm.model.lifecycle.Target;
import io.pockethive.swarm.model.lifecycle.OperationState;
import io.pockethive.swarm.model.lifecycle.TerminalResult;
import io.pockethive.swarm.model.lifecycle.TerminalStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SwarmControllerTest {

  private final SwarmStore store = new SwarmStore();
  private final SwarmOperationCoordinator operations = new SwarmOperationCoordinator();
  private final SwarmLifecycleCommandService lifecycleCommands = mock(SwarmLifecycleCommandService.class);
  private SwarmController controller;

  @BeforeEach
  void setUp() {
    Swarm swarm = new Swarm("alpha", "controller-1", "manager-1", "run-1");
    swarm.attachTemplate(new SwarmTemplateMetadata(
        "template-1", "controller:latest", List.of(), "demo/template-1", "demo"));
    store.register(swarm);
    OperationOutcomePublisher outcomes = mock(OperationOutcomePublisher.class);
    controller = new SwarmController(
        mock(ControlPlanePublisher.class),
        mock(ContainerLifecycleManager.class),
        operations,
        new OperationDispatchService(operations, outcomes, store),
        lifecycleCommands,
        store,
        mock(SwarmStateStore.class),
        new ObjectMapper().findAndRegisterModules(),
        mock(ScenarioClient.class),
        mock(SwarmNetworkBindingService.class),
        HiveJournal.noop(),
        mock(OrchestratorAuthorization.class),
        mock(FilesystemSwarmStartupArtifactStore.class),
        controlPlaneProperties());
  }

  @Test
  void listIncludesRegisteredSwarmWithoutStatusObservation() {
    var response = controller.list();

    assertThat(response.getBody()).singleElement().satisfies(view -> {
      assertThat(view.id()).isEqualTo("alpha");
      assertThat(view.controllerState().name()).isEqualTo("PROVISIONING");
      assertThat(view.observation()).isEmpty();
      assertThat(view.activeOperation()).isNull();
    });
  }

  @Test
  void startReturnsCanonicalOperationProjectionOwnedByOrchestrator() {
    var reservation = lifecycleReservation(OperationType.START, "corr-start", "idem-start");
    when(lifecycleCommands.dispatch(
        eq(ControlPlaneSignals.SWARM_START), eq("alpha"), eq("idem-start"), eq(Duration.ofSeconds(180))))
        .thenReturn(reservation);

    var response = controller.start("alpha", new ControlRequest("idem-start", null));

    assertThat(response.getStatusCode().value()).isEqualTo(202);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().correlationId()).isEqualTo("corr-start");
    assertThat(response.getBody().operationUrl())
        .isEqualTo("/api/swarms/alpha/operations/corr-start");
    assertThat(response.getBody().outcomeTopic())
        .isEqualTo("event.outcome.swarm-start.alpha.orchestrator.orchestrator-1");
  }

  @Test
  void stopAndRemoveUseTheSameConcreteLifecycleCommandPort() {
    when(lifecycleCommands.dispatch(
        eq(ControlPlaneSignals.SWARM_STOP), eq("alpha"), eq("idem-stop"), eq(Duration.ofSeconds(90))))
        .thenReturn(lifecycleReservation(OperationType.STOP, "corr-stop", "idem-stop"));

    controller.stop("alpha", new ControlRequest("idem-stop", null));
    operations.recordResult(
        "alpha", OperationType.STOP, new Target("swarm-controller", "controller-1"),
        "corr-stop", "idem-stop", OperationState.SUCCEEDED,
        new TerminalResult(TerminalStatus.SUCCEEDED, false, java.util.Map.of()), Instant.now());

    when(lifecycleCommands.dispatch(
        eq(ControlPlaneSignals.SWARM_REMOVE), eq("alpha"), eq("idem-remove"), eq(Duration.ofSeconds(180))))
        .thenReturn(lifecycleReservation(OperationType.REMOVE, "corr-remove", "idem-remove"));

    controller.remove("alpha", new ControlRequest("idem-remove", null));

    verify(lifecycleCommands).dispatch(
        ControlPlaneSignals.SWARM_STOP, "alpha", "idem-stop", Duration.ofSeconds(90));
    verify(lifecycleCommands).dispatch(
        ControlPlaneSignals.SWARM_REMOVE, "alpha", "idem-remove", Duration.ofSeconds(180));
  }

  @Test
  void operationEndpointNeverReturnsAnotherSwarmsCorrelation() {
    lifecycleReservation(OperationType.START, "corr-start", "idem-start");

    assertThat(controller.operation("alpha", "corr-start").getStatusCode().value()).isEqualTo(200);
    assertThat(controller.operation("other", "corr-start").getStatusCode().value()).isEqualTo(404);
  }

  private SwarmOperationCoordinator.Reservation lifecycleReservation(
      OperationType type, String correlationId, String idempotencyKey) {
    Instant now = Instant.now();
    var reservation = operations.reserve(
        "alpha", type, new Target("swarm-controller", "controller-1"),
        correlationId, idempotencyKey, now, now.plusSeconds(180));
    operations.markDispatched(correlationId, now.plusMillis(1));
    return new SwarmOperationCoordinator.Reservation(
        operations.findByCorrelation(correlationId).orElseThrow(), reservation.reused());
  }

  private static ControlPlaneProperties controlPlaneProperties() {
    ControlPlaneProperties properties = new ControlPlaneProperties();
    properties.setExchange("ph.control");
    properties.setControlQueuePrefix("ph.control");
    properties.setSwarmId("ALL");
    properties.setInstanceId("orchestrator-1");
    properties.getManager().setRole("orchestrator");
    return properties;
  }
}
