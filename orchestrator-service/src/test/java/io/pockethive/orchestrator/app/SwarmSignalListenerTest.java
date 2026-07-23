package io.pockethive.orchestrator.app;

import io.pockethive.swarm.model.NetworkMode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.CommandResult;
import io.pockethive.control.ControlScope;
import io.pockethive.control.JournalEvent;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.messaging.ControlPlaneEmitter;
import io.pockethive.controlplane.messaging.ControlPlanePublisher;
import io.pockethive.controlplane.messaging.EventMessage;
import io.pockethive.controlplane.topology.OrchestratorControlPlaneTopologyDescriptor;
import io.pockethive.orchestrator.domain.HiveJournal;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmOperationCoordinator;
import io.pockethive.orchestrator.domain.SwarmStore;
import io.pockethive.orchestrator.domain.SwarmTemplateMetadata;
import io.pockethive.controlplane.filesystem.FilesystemSwarmRemoveStore;
import io.pockethive.orchestrator.runtime.RuntimeLogSnapshotJournalService;
import io.pockethive.orchestrator.runtime.RuntimeRemovalPostconditionVerifier;
import io.pockethive.swarm.model.lifecycle.OperationState;
import io.pockethive.swarm.model.lifecycle.OperationType;
import io.pockethive.swarm.model.lifecycle.RemoveResult;
import io.pockethive.swarm.model.lifecycle.RemoveError;
import io.pockethive.swarm.model.lifecycle.RemoveResource;
import io.pockethive.swarm.model.lifecycle.RemoveResourceType;
import io.pockethive.swarm.model.lifecycle.Target;
import io.pockethive.swarm.model.lifecycle.TerminalResult;
import io.pockethive.swarm.model.lifecycle.TerminalStatus;
import io.pockethive.swarm.model.lifecycle.ControllerState;
import io.pockethive.swarm.model.lifecycle.Health;
import io.pockethive.swarm.model.lifecycle.RuntimeResourceState;
import io.pockethive.swarm.model.lifecycle.WorkloadState;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SwarmSignalListenerTest {

  private static final String SWARM_ID = "swarm-test";
  private static final String CONTROLLER = "controller-1";
  private static final Target TARGET = new Target("swarm-controller", CONTROLLER);

  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
  private final SwarmStore store = new SwarmStore();
  private final SwarmOperationCoordinator operations = new SwarmOperationCoordinator();
  private final CapturingPublisher transport = new CapturingPublisher();
  private final HiveJournal journal = mock(HiveJournal.class);
  private SwarmSignalListener listener;

  @BeforeEach
  void setUp() {
    Swarm swarm = new Swarm(SWARM_ID, CONTROLLER, "container-1", "run-1", NetworkMode.DIRECT);
    swarm.attachTemplate(new SwarmTemplateMetadata(
        "template-1", "controller:latest", List.of(), "demo/template-1", "demo"));
    store.register(swarm);
    OperationOutcomePublisher outcomes = new OperationOutcomePublisher(transport, mapper, "orchestrator-1");
    var descriptor = new OrchestratorControlPlaneTopologyDescriptor("ph.control");
    listener = new SwarmSignalListener(
        store,
        mock(ContainerLifecycleManager.class),
        mapper,
        journal,
        mock(ControlPlaneEmitter.class),
        mock(RuntimeLogSnapshotJournalService.class),
        operations,
        outcomes,
        mock(FilesystemSwarmRemoveStore.class),
        mock(RuntimeRemovalPostconditionVerifier.class),
        new ControlPlaneIdentity("ALL", "orchestrator", "orchestrator-1"),
        descriptor,
        "ph.control.orchestrator.orchestrator-1");
  }

  @Test
  void exactExecutorResultCompletesOperationAndPublishesOneOutcome() throws Exception {
    reserveStart();

    listener.handle(result(CONTROLLER), route(CONTROLLER));
    listener.handle(result(CONTROLLER), route(CONTROLLER));

    assertThat(operations.findByCorrelation("corr-1"))
        .map(operation -> operation.state())
        .contains(OperationState.SUCCEEDED);
    assertThat(transport.events).hasSize(1);
    assertThat(transport.events.getFirst().routingKey())
        .isEqualTo("event.outcome.swarm-start.swarm-test.orchestrator.orchestrator-1");
  }

  @Test
  void resultFromAnotherConcreteTargetCannotCompleteOperation() throws Exception {
    reserveStart();

    assertThatCode(() -> listener.handle(result("controller-2"), route("controller-2")))
        .doesNotThrowAnyException();

    assertThat(operations.findByCorrelation("corr-1"))
        .map(operation -> operation.state())
        .contains(OperationState.DISPATCHED);
    assertThat(transport.events).isEmpty();
  }

  @Test
  void executorResultWithoutOrchestratorOwnedOperationIsNotAnInvalidEvent() throws Exception {
    Target worker = new Target("generator", "generator-1");

    listener.handle(
        configResult(worker, true),
        "event.result.config-update.swarm-test.generator.generator-1");

    verify(journal, never()).append(any(HiveJournal.HiveJournalEntry.class));
    assertThat(transport.events).isEmpty();
  }

  @Test
  void lateResultAfterTimeoutDoesNotOverwriteOrRepublish() throws Exception {
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

    listener.handle(result(CONTROLLER), route(CONTROLLER));

    assertThat(operations.findByCorrelation("corr-1"))
        .map(operation -> operation.state())
        .contains(OperationState.TIMED_OUT);
    assertThat(transport.events).isEmpty();
  }

  @Test
  void journalEvidenceIsPersistedButNeverCompletesAnOperation() throws Exception {
    reserveStart();
    JournalEvent event = new JournalEvent(
        Instant.now(), "2", "journal", "work-journal", CONTROLLER,
        new ControlScope(SWARM_ID, "generator", "generator-1"),
        "journal-corr", "journal-idem",
        Map.of("templateId", "template-1", "runId", "run-1"),
        Map.of("status", "recorded"));

    listener.handle(
        mapper.writeValueAsString(event),
        "event.journal.work-journal.swarm-test.generator.generator-1");

    verify(journal).append(any(HiveJournal.HiveJournalEntry.class));
    assertThat(operations.findByCorrelation("corr-1"))
        .map(operation -> operation.state())
        .contains(OperationState.DISPATCHED);
    assertThat(transport.events).isEmpty();
  }

  @Test
  void malformedTransportInputIsDropped() {
    assertThatCode(() -> listener.handle("{}", " ")).doesNotThrowAnyException();
    verify(journal).append(any(HiveJournal.HiveJournalEntry.class));
  }

  @Test
  void enabledConfigResultWaitsForFreshMatchingTargetObservation() throws Exception {
    Target worker = new Target("generator", "generator-1");
    Instant createdAt = Instant.now().minusSeconds(2);
    operations.reserve(
        SWARM_ID, OperationType.CONFIG_UPDATE, worker,
        "config-corr", "config-idem", createdAt, createdAt.plusSeconds(60));
    operations.markDispatched("config-corr", createdAt.plusSeconds(1));
    operations.registerConfigExpectation(
        "config-corr", SwarmOperationCoordinator.ConfigEnabledExpectation.ENABLED);

    listener.handle(configResult(worker, true),
        "event.result.config-update.swarm-test.generator.generator-1");

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
    listener.handleControllerObservation(SWARM_ID);

    assertThat(operations.findByCorrelation("config-corr"))
        .map(operation -> operation.state())
        .contains(OperationState.SUCCEEDED);
    assertThat(transport.events).singleElement().satisfies(event ->
        assertThat(event.routingKey())
            .isEqualTo("event.outcome.config-update.swarm-test.orchestrator.orchestrator-1"));
  }

  @Test
  void removeCannotSucceedWhenRuntimeDirectoryCleanupFails() {
    ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
    FilesystemSwarmRemoveStore removeStore = mock(FilesystemSwarmRemoveStore.class);
    RuntimeRemovalPostconditionVerifier verifier = mock(RuntimeRemovalPostconditionVerifier.class);
    listener = listener(lifecycle, removeStore, verifier);
    Instant now = reserveRemove();
    when(removeStore.findResult(SWARM_ID, "remove-corr")).thenReturn(Optional.of(
        RemoveResult.succeeded(
            SWARM_ID, "run-1", CONTROLLER, "remove-corr", "remove-idem", List.of(), now)));
    when(lifecycle.removeControllerRuntime(SWARM_ID)).thenReturn(
        new ContainerLifecycleManager.ControllerRuntimeRemoval(List.of(), List.of(), List.of()));
    when(verifier.verifyAbsent(List.of())).thenReturn(
        new RuntimeRemovalPostconditionVerifier.Verification(List.of(), List.of(), List.of()));
    doThrow(new IllegalStateException("runtime directory is busy"))
        .when(removeStore).deleteSwarmRuntime(SWARM_ID);

    listener.checkTimeouts();

    assertThat(operations.findByCorrelation("remove-corr"))
        .map(operation -> operation.state())
        .contains(OperationState.FAILED);
    assertThat(store.find(SWARM_ID)).isPresent();
    assertThat(transport.events).singleElement().satisfies(event ->
        assertThat(event.routingKey())
            .isEqualTo("event.outcome.swarm-remove.swarm-test.orchestrator.orchestrator-1"));
  }

  @Test
  void removeCannotCleanupArtifactsOrRegistryWhileRuntimeResourceRemains() {
    ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
    FilesystemSwarmRemoveStore removeStore = mock(FilesystemSwarmRemoveStore.class);
    RuntimeRemovalPostconditionVerifier verifier = mock(RuntimeRemovalPostconditionVerifier.class);
    listener = listener(lifecycle, removeStore, verifier);
    Instant now = reserveRemove();
    RemoveResource worker = new RemoveResource(RemoveResourceType.WORKER_RUNTIME, "worker-1");
    RemoveResource controller = new RemoveResource(RemoveResourceType.CONTROLLER_RUNTIME, "container-1");
    when(removeStore.findResult(SWARM_ID, "remove-corr")).thenReturn(Optional.of(
        RemoveResult.succeeded(
            SWARM_ID, "run-1", CONTROLLER, "remove-corr", "remove-idem", List.of(worker), now)));
    when(lifecycle.removeControllerRuntime(SWARM_ID)).thenReturn(
        new ContainerLifecycleManager.ControllerRuntimeRemoval(List.of(controller), List.of(), List.of()));
    when(verifier.verifyAbsent(List.of(worker, controller))).thenReturn(
        new RuntimeRemovalPostconditionVerifier.Verification(
            List.of(controller),
            List.of(worker),
            List.of(new RemoveError("RESOURCE_STILL_PRESENT", "worker is still running", worker))));

    listener.checkTimeouts();

    assertThat(operations.findByCorrelation("remove-corr"))
        .map(operation -> operation.state())
        .contains(OperationState.FAILED);
    assertThat(store.find(SWARM_ID)).isPresent();
    verify(removeStore, never()).deleteSwarmRuntime(SWARM_ID);
  }

  @Test
  void removeSucceedsOnlyAfterRuntimeDirectoryAndRegistryAreAbsent() {
    ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
    FilesystemSwarmRemoveStore removeStore = mock(FilesystemSwarmRemoveStore.class);
    RuntimeRemovalPostconditionVerifier verifier = mock(RuntimeRemovalPostconditionVerifier.class);
    listener = listener(lifecycle, removeStore, verifier);
    Instant now = reserveRemove();
    RemoveResource worker = new RemoveResource(RemoveResourceType.WORKER_RUNTIME, "worker-1");
    RemoveResource controller = new RemoveResource(RemoveResourceType.CONTROLLER_RUNTIME, "container-1");
    when(removeStore.findResult(SWARM_ID, "remove-corr")).thenReturn(Optional.of(
        RemoveResult.succeeded(
            SWARM_ID, "run-1", CONTROLLER, "remove-corr", "remove-idem", List.of(worker), now)));
    when(lifecycle.removeControllerRuntime(SWARM_ID)).thenReturn(
        new ContainerLifecycleManager.ControllerRuntimeRemoval(List.of(controller), List.of(), List.of()));
    when(verifier.verifyAbsent(List.of(worker, controller))).thenReturn(
        new RuntimeRemovalPostconditionVerifier.Verification(
            List.of(worker, controller), List.of(), List.of()));
    when(removeStore.swarmRuntimeExists(SWARM_ID)).thenReturn(false);

    listener.checkTimeouts();

    assertThat(operations.findByCorrelation("remove-corr"))
        .map(operation -> operation.state())
        .contains(OperationState.SUCCEEDED);
    assertThat(store.find(SWARM_ID)).isEmpty();
    verify(removeStore).deleteSwarmRuntime(SWARM_ID);
    verify(journal).appendDurably(eq("run-1"), any(HiveJournal.HiveJournalEntry.class));
    assertThat(transport.events).singleElement().satisfies(event ->
        assertThat(event.routingKey())
            .isEqualTo("event.outcome.swarm-remove.swarm-test.orchestrator.orchestrator-1"));
  }

  @Test
  void removeCannotSucceedWhenDurableTerminalEvidenceCannotBeWritten() {
    ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
    FilesystemSwarmRemoveStore removeStore = mock(FilesystemSwarmRemoveStore.class);
    RuntimeRemovalPostconditionVerifier verifier = mock(RuntimeRemovalPostconditionVerifier.class);
    listener = listener(lifecycle, removeStore, verifier);
    Instant now = reserveRemove();
    when(removeStore.findResult(SWARM_ID, "remove-corr")).thenReturn(Optional.of(
        RemoveResult.succeeded(
            SWARM_ID, "run-1", CONTROLLER, "remove-corr", "remove-idem", List.of(), now)));
    when(lifecycle.removeControllerRuntime(SWARM_ID)).thenReturn(
        new ContainerLifecycleManager.ControllerRuntimeRemoval(List.of(), List.of(), List.of()));
    when(verifier.verifyAbsent(List.of())).thenReturn(
        new RuntimeRemovalPostconditionVerifier.Verification(List.of(), List.of(), List.of()));
    when(removeStore.swarmRuntimeExists(SWARM_ID)).thenReturn(false);
    doThrow(new IllegalStateException("journal unavailable"))
        .when(journal).appendDurably(eq("run-1"), any(HiveJournal.HiveJournalEntry.class));

    listener.checkTimeouts();

    assertThat(operations.findByCorrelation("remove-corr"))
        .map(operation -> operation.state())
        .contains(OperationState.FAILED);
    assertThat(store.find(SWARM_ID)).isEmpty();
    assertThat(operations.findByCorrelation("remove-corr").orElseThrow().terminalResult().context())
        .extracting("remainingResources")
        .asList()
        .contains(new RemoveResource(RemoveResourceType.TERMINAL_EVIDENCE, "remove-corr"));
  }

  private void reserveStart() {
    Instant now = Instant.now();
    operations.reserve(
        SWARM_ID, OperationType.START, TARGET, "corr-1", "idem-1", now, now.plusSeconds(30));
    operations.markDispatched("corr-1", now.plusMillis(1));
  }

  private Instant reserveRemove() {
    Instant now = Instant.now();
    operations.reserve(
        SWARM_ID, OperationType.REMOVE, TARGET,
        "remove-corr", "remove-idem", now, now.plusSeconds(30));
    operations.markDispatched("remove-corr", now.plusMillis(1));
    return now;
  }

  private SwarmSignalListener listener(
      ContainerLifecycleManager lifecycle,
      FilesystemSwarmRemoveStore removeStore,
      RuntimeRemovalPostconditionVerifier verifier) {
    return new SwarmSignalListener(
        store,
        lifecycle,
        mapper,
        journal,
        mock(ControlPlaneEmitter.class),
        mock(RuntimeLogSnapshotJournalService.class),
        operations,
        new OperationOutcomePublisher(transport, mapper, "orchestrator-1"),
        removeStore,
        verifier,
        new ControlPlaneIdentity("ALL", "orchestrator", "orchestrator-1"),
        new OrchestratorControlPlaneTopologyDescriptor("ph.control"),
        "ph.control.orchestrator.orchestrator-1");
  }

  private String result(String controller) throws Exception {
    CommandResult result = new CommandResult(
        Instant.now(), "2", "result", "swarm-start", controller,
        new ControlScope(SWARM_ID, "swarm-controller", controller),
        "corr-1", "idem-1",
        Map.of("templateId", "template-1", "runId", "run-1"),
        new TerminalResult(TerminalStatus.SUCCEEDED, false, Map.of(
            "target", new Target("swarm-controller", controller),
            "requestedWorkloadState", "RUNNING",
            "observedWorkloadState", "RUNNING",
            "nonConvergedWorkers", List.of())));
    return mapper.writeValueAsString(result);
  }

  private String configResult(Target target, boolean enabled) throws Exception {
    return mapper.writeValueAsString(new CommandResult(
        Instant.now(), "2", "result", "config-update", target.instance(),
        new ControlScope(SWARM_ID, target.role(), target.instance()),
        "config-corr", "config-idem",
        Map.of("templateId", "template-1", "runId", "run-1"),
        new TerminalResult(TerminalStatus.SUCCEEDED, false, Map.of(
            "target", target,
            "requestedEnabled", enabled,
            "observedEnabled", enabled,
            "appliedConfigSha256", "a".repeat(64)))));
  }

  private static String route(String controller) {
    return "event.result.swarm-start." + SWARM_ID + ".swarm-controller." + controller;
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
