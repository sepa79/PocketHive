package io.pockethive.orchestrator.app;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.messaging.ControlPlaneEmitter;
import io.pockethive.controlplane.topology.OrchestratorControlPlaneTopologyDescriptor;
import io.pockethive.manager.runtime.ComputeAdapterType;
import io.pockethive.observability.StatusEnvelopeBuilder;
import io.pockethive.orchestrator.domain.SwarmStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OrchestratorStatusPublisherTest {

  private static final String CONTROL_QUEUE = "ph.control.orchestrator.orchestrator-1";

  @Test
  void fullStatusIncludesRuntimeIdentityAndComputeAdapter() {
    SwarmStore store = mock(SwarmStore.class);
    ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
    ControlPlaneEmitter emitter = mock(ControlPlaneEmitter.class);
    when(store.count()).thenReturn(3);
    when(lifecycle.currentComputeAdapterType()).thenReturn(ComputeAdapterType.DOCKER_SINGLE);

    publisher(store, lifecycle, emitter).publishFull();

    ArgumentCaptor<ControlPlaneEmitter.StatusContext> context =
        ArgumentCaptor.forClass(ControlPlaneEmitter.StatusContext.class);
    verify(emitter).emitStatusSnapshot(context.capture());
    StatusEnvelopeBuilder builder = mock(StatusEnvelopeBuilder.class, RETURNS_SELF);
    context.getValue().customiser().accept(builder);

    verify(builder).workPlaneEnabled(false);
    verify(builder).enabledRequired(false);
    verify(builder).filesystemEnabled(true);
    verify(builder).tpsEnabled(false);
    verify(builder).controlIn(CONTROL_QUEUE);
    verify(builder).data("swarmCount", 3);
    verify(builder).data(eq("startedAt"), org.mockito.ArgumentMatchers.any());
    verify(builder).data("computeAdapter", "DOCKER_SINGLE");
  }

  @Test
  void periodicDeltaContainsOnlyCurrentOperationalSummary() {
    SwarmStore store = mock(SwarmStore.class);
    ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
    ControlPlaneEmitter emitter = mock(ControlPlaneEmitter.class);
    when(store.count()).thenReturn(2);

    publisher(store, lifecycle, emitter).publishDelta();

    ArgumentCaptor<ControlPlaneEmitter.StatusContext> context =
        ArgumentCaptor.forClass(ControlPlaneEmitter.StatusContext.class);
    verify(emitter).emitStatusDelta(context.capture());
    StatusEnvelopeBuilder builder = mock(StatusEnvelopeBuilder.class, RETURNS_SELF);
    context.getValue().customiser().accept(builder);

    verify(builder).workPlaneEnabled(false);
    verify(builder).enabledRequired(false);
    verify(builder).tpsEnabled(false);
    verify(builder).controlIn(CONTROL_QUEUE);
    verify(builder).data("swarmCount", 2);
  }

  private static OrchestratorStatusPublisher publisher(
      SwarmStore store, ContainerLifecycleManager lifecycle, ControlPlaneEmitter emitter) {
    return new OrchestratorStatusPublisher(
        store,
        lifecycle,
        emitter,
        new ControlPlaneIdentity("ALL", "orchestrator", "orchestrator-1"),
        new OrchestratorControlPlaneTopologyDescriptor("ph.control"),
        CONTROL_QUEUE);
  }
}
