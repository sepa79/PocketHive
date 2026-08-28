package io.pockethive.orchestrator.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.CommandOutcome;
import io.pockethive.control.CommandResult;
import io.pockethive.control.ControlScope;
import io.pockethive.controlplane.ControlPlaneRoles;
import io.pockethive.controlplane.codec.ControlPlaneCodec;
import io.pockethive.controlplane.messaging.ControlPlanePublisher;
import io.pockethive.controlplane.messaging.EventMessage;
import io.pockethive.controlplane.routing.ControlPlaneRouting.RoutingKey;
import io.pockethive.orchestrator.domain.HiveJournal;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmOperationCoordinator;
import io.pockethive.orchestrator.domain.SwarmStore;
import io.pockethive.orchestrator.domain.SwarmTemplateMetadata;
import io.pockethive.swarm.model.NetworkMode;
import io.pockethive.swarm.model.lifecycle.ControllerState;
import io.pockethive.swarm.model.lifecycle.Health;
import io.pockethive.swarm.model.lifecycle.OperationState;
import io.pockethive.swarm.model.lifecycle.OperationType;
import io.pockethive.swarm.model.lifecycle.RuntimeMetadata;
import io.pockethive.swarm.model.lifecycle.RuntimeResourceState;
import io.pockethive.swarm.model.lifecycle.Target;
import io.pockethive.swarm.model.lifecycle.TerminalResult;
import io.pockethive.swarm.model.lifecycle.TerminalStatus;
import io.pockethive.swarm.model.lifecycle.WorkloadState;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SwarmOperationTerminalHandlerTest {

  private static final String SWARM_ID = "swarm-test";
  private static final String CONTROLLER = "controller-1";
  private static final Target TARGET = new Target(ControlPlaneRoles.SWARM_CONTROLLER, CONTROLLER);

  private final ControlPlaneCodec codec = ControlPlaneCodec.create();
  private final SwarmStore store = new SwarmStore();
  private final SwarmOperationCoordinator operations = new SwarmOperationCoordinator();
  private final CapturingPublisher transport = new CapturingPublisher();
  private final HiveJournal journal = mock(HiveJournal.class);
  private final SwarmRemovalConvergenceHandler removals = mock(SwarmRemovalConvergenceHandler.class);
  private SwarmOperationObservationHandler observations;
  private SwarmOperationTerminalHandler handler;

  @BeforeEach
  void setUp() {
    Swarm swarm = new Swarm(SWARM_ID, CONTROLLER, "container-1", "run-1", NetworkMode.DIRECT);
    swarm.attachTemplate(new SwarmTemplateMetadata(
        "template-1", "controller:latest", List.of(), "demo/template-1", "demo"));
    store.register(swarm);
    OperationOutcomePublisher outcomes = new OperationOutcomePublisher(transport, "orchestrator-1");
    observations = new SwarmOperationObservationHandler(store, operations, outcomes);
    handler = new SwarmOperationTerminalHandler(
        store,
        new ObjectMapper(),
        journal,
        operations,
        outcomes,
        observations,
        removals);
  }

  @Test
  void exactExecutorResultCompletesOperationAndPublishesOneOutcome() {
    reserveStart();

    handler.accept(key(CONTROLLER), route(CONTROLLER), result(CONTROLLER));
    handler.accept(key(CONTROLLER), route(CONTROLLER), result(CONTROLLER));

    assertThat(operations.findByCorrelation("corr-1"))
        .map(operation -> operation.state())
        .contains(OperationState.SUCCEEDED);
    assertThat(transport.events).hasSize(1);
    assertThat(transport.events.getFirst().routingKey())
        .isEqualTo("event.outcome.swarm-start.swarm-test.orchestrator.orchestrator-1");
  }

  @Test
  void resultFromAnotherConcreteTargetCannotCompleteOperation() {
    reserveStart();

    handler.accept(key("controller-2"), route("controller-2"), result("controller-2"));

    assertThat(operations.findByCorrelation("corr-1"))
        .map(operation -> operation.state())
        .contains(OperationState.DISPATCHED);
    assertThat(transport.events).isEmpty();
  }

  @Test
  void executorResultWithoutOrchestratorOwnedOperationIsIgnored() {
    Target worker = new Target("generator", "generator-1");
    RoutingKey key = new RoutingKey("event", "result.config-update", SWARM_ID, worker.role(), worker.instance());

    handler.accept(key, "event.result.config-update.swarm-test.generator.generator-1", configResult(worker, true));

    verify(journal, never()).append(any(HiveJournal.HiveJournalEntry.class));
    assertThat(transport.events).isEmpty();
  }

  @Test
  void lateResultAfterTimeoutDoesNotOverwriteOrRepublish() {
    reserveStart();
    operations.recordResult(
        SWARM_ID,
        OperationType.START,
        TARGET,
        "corr-1",
        "idem-1",
        OperationState.TIMED_OUT,
        new TerminalResult(TerminalStatus.TIMED_OUT, true, Map.of()),
        Instant.now());

    handler.accept(key(CONTROLLER), route(CONTROLLER), result(CONTROLLER));

    assertThat(operations.findByCorrelation("corr-1"))
        .map(operation -> operation.state())
        .contains(OperationState.TIMED_OUT);
    assertThat(transport.events).isEmpty();
  }

  @Test
  void enabledConfigResultWaitsForFreshMatchingTargetObservation() {
    Target worker = new Target("generator", "generator-1");
    Instant createdAt = Instant.now().minusSeconds(2);
    operations.reserve(
        SWARM_ID, OperationType.CONFIG_UPDATE, worker,
        new RuntimeMetadata("template-1", "run-1"),
        "config-corr", "config-idem", createdAt, createdAt.plusSeconds(60));
    operations.markDispatched("config-corr", createdAt.plusSeconds(1));
    operations.registerConfigExpectation(
        "config-corr", SwarmOperationCoordinator.ConfigEnabledExpectation.ENABLED);
    RoutingKey key = new RoutingKey("event", "result.config-update", SWARM_ID, worker.role(), worker.instance());

    handler.accept(key, "event.result.config-update.swarm-test.generator.generator-1", configResult(worker, true));

    assertThat(operations.findByCorrelation("config-corr"))
        .map(operation -> operation.state())
        .contains(OperationState.DISPATCHED);
    assertThat(transport.events).isEmpty();

    store.find(SWARM_ID).orElseThrow().updateObservation(
        ControllerState.READY, WorkloadState.RUNNING, Health.HEALTHY,
        RuntimeResourceState.PRESENT,
        Map.of("workers", List.of(Map.of(
            "role", "generator",
            "instance", "generator-1",
            "enabled", true,
            "lastSeenAt", Instant.now().toString()))),
        Instant.now());
    observations.handleControllerObservation(SWARM_ID);

    assertThat(operations.findByCorrelation("config-corr"))
        .map(operation -> operation.state())
        .contains(OperationState.SUCCEEDED);
    assertThat(transport.events).singleElement().satisfies(event ->
        assertThat(event.routingKey())
            .isEqualTo("event.outcome.config-update.swarm-test.orchestrator.orchestrator-1"));
  }

  @Test
  void expiredOperationPublishesCanonicalTimeoutOutcome() {
    Instant createdAt = Instant.now().minusSeconds(60);
    operations.reserve(
        SWARM_ID, OperationType.START, TARGET,
        new RuntimeMetadata("template-1", "run-1"),
        "timeout-corr", "timeout-idem", createdAt, createdAt.plusSeconds(30));
    operations.markDispatched("timeout-corr", createdAt.plusSeconds(1));

    handler.checkTimeouts();

    assertThat(operations.findByCorrelation("timeout-corr"))
        .map(operation -> operation.state())
        .contains(OperationState.TIMED_OUT);
    assertThat(operations.findByCorrelation("timeout-corr").orElseThrow().terminalResult().context())
        .containsEntry("requestedWorkloadState", "RUNNING")
        .containsEntry("observedWorkloadState", "UNAVAILABLE");
    assertThat(transport.events).hasSize(1);
    verify(removals).checkResults();
  }

  @Test
  void retryPublishesPreRegistrationCreateOutcomeFromTheOperationRuntime() {
    store.remove(SWARM_ID);
    RuntimeMetadata runtime = new RuntimeMetadata("template-1", "planned-run-1");
    Instant now = Instant.now();
    operations.reserve(
        SWARM_ID, OperationType.CREATE, TARGET, runtime,
        "create-corr", "create-idem", now, now.plusSeconds(30));
    operations.markDispatched("create-corr", now.plusMillis(1));
    operations.recordResult(
        SWARM_ID, OperationType.CREATE, TARGET,
        "create-corr", "create-idem", OperationState.FAILED,
        new TerminalResult(TerminalStatus.FAILED, true, Map.of(
            "target", TARGET,
            "runtimeIntent", "PRESENT",
            "controllerState", "UNKNOWN",
            "workloadState", "UNKNOWN",
            "startupArtifactSha256", "missing")),
        now.plusSeconds(1));

    handler.checkTimeouts();

    assertThat(transport.events).singleElement().satisfies(event -> {
      CommandOutcome outcome = (CommandOutcome) event.payload();
      assertThat(outcome.runtime()).containsExactlyInAnyOrderEntriesOf(runtime.asControlPlaneRuntime());
      assertThatCode(() -> codec.encode(outcome, event.routingKey())).doesNotThrowAnyException();
    });
  }

  private void reserveStart() {
    Instant now = Instant.now();
    operations.reserve(
        SWARM_ID, OperationType.START, TARGET,
        new RuntimeMetadata("template-1", "run-1"),
        "corr-1", "idem-1", now, now.plusSeconds(30));
    operations.markDispatched("corr-1", now.plusMillis(1));
  }

  private static CommandResult result(String controller) {
    return new CommandResult(
        Instant.now(), "2", "result", "swarm-start", controller,
        new ControlScope(SWARM_ID, ControlPlaneRoles.SWARM_CONTROLLER, controller),
        "corr-1", "idem-1",
        Map.of("templateId", "template-1", "runId", "run-1"),
        new TerminalResult(TerminalStatus.SUCCEEDED, false, Map.of(
            "target", new Target(ControlPlaneRoles.SWARM_CONTROLLER, controller),
            "requestedWorkloadState", "RUNNING",
            "observedWorkloadState", "RUNNING",
            "nonConvergedWorkers", List.of())));
  }

  private static CommandResult configResult(Target target, boolean enabled) {
    return new CommandResult(
        Instant.now(), "2", "result", "config-update", target.instance(),
        new ControlScope(SWARM_ID, target.role(), target.instance()),
        "config-corr", "config-idem",
        Map.of("templateId", "template-1", "runId", "run-1"),
        new TerminalResult(TerminalStatus.SUCCEEDED, false, Map.of(
            "target", target,
            "requestedEnabled", enabled,
            "observedEnabled", enabled,
            "appliedConfigSha256", "a".repeat(64))));
  }

  private static RoutingKey key(String controller) {
    return new RoutingKey(
        "event", "result.swarm-start", SWARM_ID, ControlPlaneRoles.SWARM_CONTROLLER, controller);
  }

  private static String route(String controller) {
    return "event.result.swarm-start." + SWARM_ID + "." + ControlPlaneRoles.SWARM_CONTROLLER + "." + controller;
  }

  private static final class CapturingPublisher implements ControlPlanePublisher {
    private final List<EventMessage> events = new ArrayList<>();

    @Override
    public void publishSignal(io.pockethive.controlplane.messaging.SignalMessage message) {
    }

    @Override
    public void publishEvent(EventMessage message) {
      events.add(message);
    }
  }
}
