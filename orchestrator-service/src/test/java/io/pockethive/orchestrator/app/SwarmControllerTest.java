package io.pockethive.orchestrator.app;

import io.pockethive.swarm.model.NetworkMode;

import io.pockethive.swarm.model.lifecycle.ControlRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.auth.contract.AuthProvider;
import io.pockethive.auth.contract.AuthenticatedUserDto;
import io.pockethive.controlplane.messaging.ControlPlanePublisher;
import io.pockethive.controlplane.spring.ControlPlaneProperties;
import io.pockethive.orchestrator.auth.OrchestratorAuthorization;
import io.pockethive.orchestrator.auth.OrchestratorCurrentUserHolder;
import io.pockethive.orchestrator.domain.HiveJournal;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.OperationConflictException;
import io.pockethive.orchestrator.domain.SwarmOperationCoordinator;
import io.pockethive.orchestrator.domain.SwarmStateStore;
import io.pockethive.orchestrator.domain.SwarmStore;
import io.pockethive.orchestrator.domain.SwarmTemplateMetadata;
import io.pockethive.controlplane.filesystem.FilesystemSwarmStartupArtifactStore;
import io.pockethive.swarm.model.lifecycle.OperationType;
import io.pockethive.swarm.model.lifecycle.SwarmCreateRequest;
import io.pockethive.swarm.model.lifecycle.Target;
import io.pockethive.swarm.model.lifecycle.OperationState;
import io.pockethive.swarm.model.lifecycle.TerminalResult;
import io.pockethive.swarm.model.lifecycle.TerminalStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SwarmControllerTest {

  private final SwarmStore store = new SwarmStore();
  private final SwarmOperationCoordinator operations = new SwarmOperationCoordinator();
  private final SwarmLifecycleCommandService lifecycleCommands = mock(SwarmLifecycleCommandService.class);
  private final ScenarioClient scenarios = mock(ScenarioClient.class);
  private final OrchestratorAuthorization authorization = mock(OrchestratorAuthorization.class);
  private SwarmController controller;

  @BeforeEach
  void setUp() {
    Swarm swarm = new Swarm("alpha", "controller-1", "manager-1", "run-1", NetworkMode.DIRECT);
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
        new ControlResponseFactory(controlPlaneProperties()),
        store,
        mock(SwarmStateStore.class),
        new ObjectMapper().findAndRegisterModules(),
        scenarios,
        mock(SwarmNetworkBindingService.class),
        HiveJournal.noop(),
        authorization,
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
        eq(OperationType.START), eq("alpha"), eq("idem-start"), eq(Duration.ofSeconds(180))))
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
        eq(OperationType.STOP), eq("alpha"), eq("idem-stop"), eq(Duration.ofSeconds(90))))
        .thenReturn(lifecycleReservation(OperationType.STOP, "corr-stop", "idem-stop"));

    controller.stop("alpha", new ControlRequest("idem-stop", null));
    operations.recordResult(
        "alpha", OperationType.STOP, new Target("swarm-controller", "controller-1"),
        "corr-stop", "idem-stop", OperationState.SUCCEEDED,
        new TerminalResult(TerminalStatus.SUCCEEDED, false, java.util.Map.of()), Instant.now());

    when(lifecycleCommands.dispatch(
        eq(OperationType.REMOVE), eq("alpha"), eq("idem-remove"), eq(Duration.ofSeconds(180))))
        .thenReturn(lifecycleReservation(OperationType.REMOVE, "corr-remove", "idem-remove"));

    controller.remove("alpha", new ControlRequest("idem-remove", null));

    verify(lifecycleCommands).dispatch(
        OperationType.STOP, "alpha", "idem-stop", Duration.ofSeconds(90));
    verify(lifecycleCommands).dispatch(
        OperationType.REMOVE, "alpha", "idem-remove", Duration.ofSeconds(180));
  }

  @Test
  void operationEndpointNeverReturnsAnotherSwarmsCorrelation() {
    lifecycleReservation(OperationType.START, "corr-start", "idem-start");

    assertThat(controller.operation("alpha", "corr-start").getStatusCode().value()).isEqualTo(200);
    assertThat(controller.operation("other", "corr-start").getStatusCode().value()).isEqualTo(404);
  }

  @Test
  void lifecycleConflictIsExposedAsHttp409WithTheActiveOperation() throws Exception {
    var reservation = lifecycleReservation(OperationType.CREATE, "corr-create", "idem-create");
    when(lifecycleCommands.dispatch(
        eq(OperationType.STOP), eq("alpha"), eq("idem-stop"), eq(Duration.ofSeconds(90))))
        .thenThrow(new OperationConflictException(reservation.operation()));

    MockMvcBuilders.standaloneSetup(controller).build()
        .perform(post("/api/swarms/alpha/stop")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"idempotencyKey\":\"idem-stop\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.correlationId").value("corr-create"))
        .andExpect(jsonPath("$.type").value("CREATE"))
        .andExpect(jsonPath("$.state").value("DISPATCHED"));
  }

  @Test
  void deniedCreateDoesNotReserveALifecycleOperation() throws Exception {
    var descriptor = new ScenarioClient.ScenarioTemplateDescriptor(
        "template-denied", "restricted/template-denied", "restricted/template-denied", "restricted", false);
    when(scenarios.fetchScenarioTemplate("template-denied")).thenReturn(descriptor);
    when(authorization.canRun(any(), eq(descriptor))).thenReturn(false);
    when(authorization.runDeniedMessage()).thenReturn("Template run denied");
    var user = new AuthenticatedUserDto(
        UUID.fromString("11111111-1111-1111-1111-111111111111"),
        "denied-user",
        "Denied User",
        true,
        AuthProvider.DEV,
        List.of());

    try {
      OrchestratorCurrentUserHolder.set(user);
      assertThatThrownBy(() -> controller.create("denied-swarm", new SwarmCreateRequest(
          "template-denied", "idem-denied", null, false, null, null, NetworkMode.DIRECT, null)))
          .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
          .hasMessageContaining("403 FORBIDDEN");
    } finally {
      OrchestratorCurrentUserHolder.clear();
    }

    assertThat(operations.operations()).isEmpty();
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
