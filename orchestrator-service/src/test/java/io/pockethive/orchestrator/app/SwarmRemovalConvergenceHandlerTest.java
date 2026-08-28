package io.pockethive.orchestrator.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.ControlPlaneRoles;
import io.pockethive.controlplane.ControlPlaneSignals;
import io.pockethive.controlplane.filesystem.FilesystemSwarmRemoveStore;
import io.pockethive.controlplane.messaging.ControlPlanePublisher;
import io.pockethive.controlplane.messaging.EventMessage;
import io.pockethive.orchestrator.domain.HiveJournal;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmOperationCoordinator;
import io.pockethive.orchestrator.domain.SwarmStore;
import io.pockethive.orchestrator.runtime.RuntimeRemovalPostconditionVerifier;
import io.pockethive.swarm.model.NetworkMode;
import io.pockethive.swarm.model.lifecycle.OperationState;
import io.pockethive.swarm.model.lifecycle.OperationType;
import io.pockethive.swarm.model.lifecycle.RemoveError;
import io.pockethive.swarm.model.lifecycle.RemoveResource;
import io.pockethive.swarm.model.lifecycle.RemoveResourceType;
import io.pockethive.swarm.model.lifecycle.RemoveResult;
import io.pockethive.swarm.model.lifecycle.RuntimeMetadata;
import io.pockethive.swarm.model.lifecycle.Target;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SwarmRemovalConvergenceHandlerTest {

  private static final String SWARM_ID = "swarm-test";
  private static final String CONTROLLER = "controller-1";
  private static final Target TARGET = new Target(ControlPlaneRoles.SWARM_CONTROLLER, CONTROLLER);

  private final SwarmStore store = new SwarmStore();
  private final SwarmOperationCoordinator operations = new SwarmOperationCoordinator();
  private final CapturingPublisher transport = new CapturingPublisher();
  private final HiveJournal journal = mock(HiveJournal.class);
  private final ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
  private final FilesystemSwarmRemoveStore removeStore = mock(FilesystemSwarmRemoveStore.class);
  private final RuntimeRemovalPostconditionVerifier verifier = mock(RuntimeRemovalPostconditionVerifier.class);
  private final SwarmNetworkBindingService networkBindings = mock(SwarmNetworkBindingService.class);
  private SwarmRemovalConvergenceHandler handler;

  @BeforeEach
  void setUp() {
    store.register(new Swarm(SWARM_ID, CONTROLLER, "container-1", "run-1", NetworkMode.DIRECT));
    handler = new SwarmRemovalConvergenceHandler(
        store,
        lifecycle,
        journal,
        operations,
        new OperationOutcomePublisher(transport, "orchestrator-1"),
        removeStore,
        verifier,
        networkBindings,
        new ControlPlaneIdentity("ALL", ControlPlaneRoles.ORCHESTRATOR, "orchestrator-1"));
  }

  @Test
  void removeCannotSucceedWhenRuntimeDirectoryCleanupFails() {
    Instant now = reserveRemove();
    succeededResult(List.of(), now);
    successfulRuntimeRemoval(List.of());
    doThrow(new IllegalStateException("runtime directory is busy"))
        .when(removeStore).deleteSwarmRuntime(SWARM_ID);

    handler.checkResults();

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
    Instant now = reserveRemove();
    RemoveResource worker = new RemoveResource(RemoveResourceType.WORKER_RUNTIME, "worker-1");
    succeededResult(List.of(worker), now);
    when(verifier.verifyAbsent(List.of(worker))).thenReturn(
        new RuntimeRemovalPostconditionVerifier.Verification(
            List.of(),
            List.of(worker),
            List.of(new RemoveError("RESOURCE_STILL_PRESENT", "worker is still running", worker))));

    handler.checkResults();

    assertThat(operations.findByCorrelation("remove-corr"))
        .map(operation -> operation.state())
        .contains(OperationState.FAILED);
    assertThat(store.find(SWARM_ID)).isPresent();
    verify(lifecycle, never()).removeControllerRuntime(SWARM_ID);
    verify(removeStore, never()).deleteSwarmRuntime(SWARM_ID);
  }

  @Test
  void removeSucceedsOnlyAfterRuntimeDirectoryAndRegistryAreAbsent() {
    Instant now = reserveRemove();
    RemoveResource worker = new RemoveResource(RemoveResourceType.WORKER_RUNTIME, "worker-1");
    RemoveResource controller = new RemoveResource(RemoveResourceType.CONTROLLER_RUNTIME, "container-1");
    succeededResult(List.of(worker), now);
    when(lifecycle.removeControllerRuntime(SWARM_ID)).thenReturn(
        new ContainerLifecycleManager.ControllerRuntimeRemoval(List.of(controller), List.of(), List.of()));
    when(verifier.verifyAbsent(List.of(worker))).thenReturn(
        new RuntimeRemovalPostconditionVerifier.Verification(List.of(worker), List.of(), List.of()));
    when(verifier.verifyAbsent(List.of(controller))).thenReturn(
        new RuntimeRemovalPostconditionVerifier.Verification(List.of(controller), List.of(), List.of()));
    when(removeStore.swarmRuntimeExists(SWARM_ID)).thenReturn(false);

    handler.checkResults();

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
    Instant now = reserveRemove();
    succeededResult(List.of(), now);
    successfulRuntimeRemoval(List.of());
    when(removeStore.swarmRuntimeExists(SWARM_ID)).thenReturn(false);
    doThrow(new IllegalStateException("journal unavailable"))
        .when(journal).appendDurably(eq("run-1"), any(HiveJournal.HiveJournalEntry.class));

    handler.checkResults();

    assertThat(operations.findByCorrelation("remove-corr"))
        .map(operation -> operation.state())
        .contains(OperationState.FAILED);
    assertThat(store.find(SWARM_ID)).isEmpty();
    assertThat(operations.findByCorrelation("remove-corr").orElseThrow().terminalResult().context())
        .extracting("remainingResources")
        .asList()
        .contains(new RemoveResource(RemoveResourceType.TERMINAL_EVIDENCE, "remove-corr"));
  }

  @Test
  void removeCannotSucceedOrTearDownWhenNetworkBindingCleanupFails() {
    Instant now = reserveRemove();
    succeededResult(List.of(), now);
    when(verifier.verifyAbsent(List.of())).thenReturn(
        new RuntimeRemovalPostconditionVerifier.Verification(List.of(), List.of(), List.of()));
    doThrow(new IllegalStateException("binding still active"))
        .when(networkBindings)
        .clearBindingAndVerifyAbsent(
            SWARM_ID,
            "remove-corr",
            "remove-idem",
            ControlPlaneRoles.ORCHESTRATOR,
            ControlPlaneSignals.SWARM_REMOVE,
            ControlPlaneRoles.ORCHESTRATOR);

    handler.checkResults();

    assertThat(operations.findByCorrelation("remove-corr"))
        .map(operation -> operation.state())
        .contains(OperationState.FAILED);
    assertThat(operations.findByCorrelation("remove-corr").orElseThrow().terminalResult().retryable())
        .isTrue();
    assertThat(operations.findByCorrelation("remove-corr").orElseThrow().terminalResult().context())
        .extracting("remainingResources")
        .asList()
        .contains(new RemoveResource(RemoveResourceType.NETWORK_BINDING, SWARM_ID));
    assertThat(store.find(SWARM_ID)).isPresent();
    verify(lifecycle, never()).removeControllerRuntime(SWARM_ID);
    verify(removeStore, never()).deleteSwarmRuntime(SWARM_ID);
  }

  @Test
  void unreadableRemoveEvidenceFailsTheOperationWithoutCleanup() {
    reserveRemove();
    when(removeStore.findResult(SWARM_ID, "remove-corr"))
        .thenThrow(new IllegalArgumentException("invalid remove evidence"));

    handler.checkResults();

    assertThat(operations.findByCorrelation("remove-corr"))
        .map(operation -> operation.state())
        .contains(OperationState.FAILED);
    assertThat(store.find(SWARM_ID)).isPresent();
    verify(lifecycle, never()).removeControllerRuntime(SWARM_ID);
    assertThat(transport.events).hasSize(1);
  }

  private Instant reserveRemove() {
    Instant now = Instant.now();
    operations.reserve(
        SWARM_ID,
        OperationType.REMOVE,
        TARGET,
        new RuntimeMetadata("template-1", "run-1"),
        "remove-corr",
        "remove-idem",
        now,
        now.plusSeconds(30));
    operations.markDispatched("remove-corr", now.plusMillis(1));
    return now;
  }

  private void succeededResult(List<RemoveResource> resources, Instant completedAt) {
    when(removeStore.findResult(SWARM_ID, "remove-corr")).thenReturn(Optional.of(
        RemoveResult.succeeded(
            SWARM_ID,
            "run-1",
            CONTROLLER,
            "remove-corr",
            "remove-idem",
            resources,
            completedAt)));
  }

  private void successfulRuntimeRemoval(List<RemoveResource> controllerResources) {
    when(lifecycle.removeControllerRuntime(SWARM_ID)).thenReturn(
        new ContainerLifecycleManager.ControllerRuntimeRemoval(controllerResources, List.of(), List.of()));
    when(verifier.verifyAbsent(List.of())).thenReturn(
        new RuntimeRemovalPostconditionVerifier.Verification(List.of(), List.of(), List.of()));
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
