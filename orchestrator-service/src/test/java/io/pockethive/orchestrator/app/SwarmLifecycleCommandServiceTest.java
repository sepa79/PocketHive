package io.pockethive.orchestrator.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.pockethive.controlplane.filesystem.FilesystemSwarmRemoveStore;
import io.pockethive.controlplane.messaging.ControlPlanePublisher;
import io.pockethive.controlplane.messaging.SignalMessage;
import io.pockethive.controlplane.spring.ControlPlaneProperties;
import io.pockethive.orchestrator.domain.*;
import io.pockethive.swarm.model.NetworkMode;
import io.pockethive.swarm.model.lifecycle.*;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class SwarmLifecycleCommandServiceTest {
  @Test
  void exactRunRejectsReplacementBeforePublishingOrChangingIntent() {
    var store = new SwarmStore();
    var swarm = new Swarm("alpha", "controller-2", "container-2", "new-run", NetworkMode.DIRECT);
    swarm.attachTemplate(new SwarmTemplateMetadata("template-1", "controller:latest", List.of()));
    store.register(swarm);
    var coordinator = new SwarmOperationCoordinator();
    var publisher = mock(ControlPlanePublisher.class);
    var properties = new ControlPlaneProperties();
    properties.setInstanceId("orchestrator-1");
    var service = new SwarmLifecycleCommandService(store,
        new OperationDispatchService(coordinator, mock(OperationOutcomePublisher.class), store),
        publisher, mock(FilesystemSwarmRemoveStore.class), mock(HiveJournal.class), properties);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.dispatchForRun(
        OperationType.START, "alpha", "old-run", "same-key", Duration.ofSeconds(30)))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("run changed");
    verifyNoInteractions(publisher);
    assertThat(coordinator.operations()).isEmpty();
    assertThat(swarm.getWorkloadIntent()).isEqualTo(WorkloadIntent.STOPPED);
  }

  @Test
  void exactRunRetriesReuseTheCanonicalOperationWithoutPublishingAgain() {
    var store = new SwarmStore();
    var swarm = new Swarm("alpha", "controller-1", "container-1", "run-1", NetworkMode.DIRECT);
    swarm.attachTemplate(new SwarmTemplateMetadata("template-1", "controller:latest", List.of()));
    store.register(swarm);
    var coordinator = new SwarmOperationCoordinator();
    var publisher = mock(ControlPlanePublisher.class);
    var properties = new ControlPlaneProperties();
    properties.setInstanceId("orchestrator-1");
    var service = new SwarmLifecycleCommandService(store,
        new OperationDispatchService(coordinator, mock(OperationOutcomePublisher.class), store),
        publisher, mock(FilesystemSwarmRemoveStore.class), mock(HiveJournal.class), properties);
    var first = service.dispatchForRun(OperationType.START, "alpha", "run-1", "same-key", Duration.ofSeconds(30));
    var retry = service.dispatchForRun(OperationType.START, "alpha", "run-1", "same-key", Duration.ofSeconds(30));
    assertThat(retry.reused()).isTrue();
    assertThat(retry.operation().correlationId()).isEqualTo(first.operation().correlationId());
    assertThat(retry.operation().runtime().runId()).isEqualTo("run-1");
    verify(publisher, times(1)).publishSignal(any());
  }

  @Test
  void replacementBetweenLookupAndReservationCannotReceiveTheOldRunCommand() {
    var store = new SwarmStore();
    var original = new Swarm("alpha", "controller-1", "container-1", "run-1", NetworkMode.DIRECT);
    original.attachTemplate(new SwarmTemplateMetadata("template-1", "controller:latest", List.of()));
    store.register(original);
    var replacement = new Swarm("alpha", "controller-2", "container-2", "run-2", NetworkMode.DIRECT);
    replacement.attachTemplate(new SwarmTemplateMetadata("template-1", "controller:latest", List.of()));
    var coordinator = new SwarmOperationCoordinator();
    var dispatch = spy(new OperationDispatchService(coordinator, mock(OperationOutcomePublisher.class), store));
    doAnswer(invocation -> {
      store.register(replacement);
      return invocation.callRealMethod();
    }).when(dispatch).dispatch(anyString(), any(), any(), anyString(), anyString(), any(), any(), any());
    var publisher = mock(ControlPlanePublisher.class);
    var properties = new ControlPlaneProperties();
    properties.setInstanceId("orchestrator-1");
    var service = new SwarmLifecycleCommandService(store, dispatch, publisher,
        mock(FilesystemSwarmRemoveStore.class), mock(HiveJournal.class), properties);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.dispatchForRun(
        OperationType.START, "alpha", "run-1", "same-key", Duration.ofSeconds(30)))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("identity changed");
    verifyNoInteractions(publisher);
    assertThat(coordinator.operations()).isEmpty();
    assertThat(replacement.getWorkloadIntent()).isEqualTo(WorkloadIntent.STOPPED);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(value = OperationType.class, names = {"START", "STOP"})
  void acceptedCommandResolvesDiscoveredUnknownIntent(OperationType command) {
    var store = new SwarmStore();
    var swarm = new Swarm("alpha", "controller-1", "container-1", "run-1", NetworkMode.DIRECT, WorkloadIntent.UNKNOWN);
    swarm.attachTemplate(new SwarmTemplateMetadata("template-1", "controller:latest", List.of()));
    store.register(swarm);
    var properties = new ControlPlaneProperties();
    properties.setInstanceId("orchestrator-1");
    var publisher = mock(ControlPlanePublisher.class);
    var service = new SwarmLifecycleCommandService(store,
        new OperationDispatchService(new SwarmOperationCoordinator(), mock(OperationOutcomePublisher.class), store),
        publisher, mock(FilesystemSwarmRemoveStore.class), mock(HiveJournal.class), properties);
    service.dispatch(command, "alpha", "command-key", Duration.ofSeconds(30));
    assertThat(swarm.getWorkloadIntent()).isEqualTo(command == OperationType.START ? WorkloadIntent.RUNNING : WorkloadIntent.STOPPED);
    verify(publisher).publishSignal(any());
  }

  @Test
  void concurrentStopWaitsForStartPublicationAndHasIndependentOutcome() throws Exception {
    var store = new SwarmStore();
    var swarm = new Swarm("alpha", "controller-1", "container-1", "run-1", NetworkMode.DIRECT);
    swarm.attachTemplate(new SwarmTemplateMetadata("template-1", "controller:latest", List.of()));
    store.register(swarm);
    var coordinator = new SwarmOperationCoordinator();
    var publisher = mock(ControlPlanePublisher.class);
    var properties = new ControlPlaneProperties();
    properties.setInstanceId("orchestrator-1");
    var service = new SwarmLifecycleCommandService(store,
        new OperationDispatchService(coordinator, mock(OperationOutcomePublisher.class), store),
        publisher, mock(FilesystemSwarmRemoveStore.class), mock(HiveJournal.class), properties);
    var startEntered = new CountDownLatch(1);
    var releaseStart = new CountDownLatch(1);
    var stopAttempted = new CountDownLatch(1);
    var stopPublished = new CountDownLatch(1);
    var publications = new CopyOnWriteArrayList<String>();
    doAnswer(call -> {
      SignalMessage message = call.getArgument(0);
      if (message.routingKey().contains("swarm-start")) {
        startEntered.countDown();
        if (!releaseStart.await(5, TimeUnit.SECONDS)) throw new AssertionError("START was not released");
        publications.add("START");
      } else {
        publications.add("STOP");
        stopPublished.countDown();
      }
      return null;
    }).when(publisher).publishSignal(any());

    try (var executor = Executors.newFixedThreadPool(2)) {
      var startFuture = executor.submit(() -> service.dispatch(OperationType.START, "alpha", "start-key", Duration.ofSeconds(30)));
      assertThat(startEntered.await(5, TimeUnit.SECONDS)).isTrue();
      var stopFuture = executor.submit(() -> {
        stopAttempted.countDown();
        return service.dispatch(OperationType.STOP, "alpha", "stop-key", Duration.ofSeconds(30));
      });
      try {
        assertThat(stopAttempted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(stopPublished.await(100, TimeUnit.MILLISECONDS)).isFalse();
      } finally {
        releaseStart.countDown();
      }
      var start = startFuture.get(5, TimeUnit.SECONDS).operation();
      var stop = stopFuture.get(5, TimeUnit.SECONDS).operation();
      assertThat(publications).containsExactly("START", "STOP");
      assertThat(swarm.getWorkloadIntent()).isEqualTo(WorkloadIntent.STOPPED);
      assertThat(coordinator.recordResult("alpha", OperationType.START, start.target(), start.correlationId(),
          start.idempotencyKey(), OperationState.FAILED,
          new TerminalResult(TerminalStatus.FAILED, true, Map.of()), Instant.now()))
          .isEqualTo(OperationCompletion.COMPLETED);
      assertThat(coordinator.recordResult("alpha", OperationType.START, start.target(), start.correlationId(),
          start.idempotencyKey(), OperationState.SUCCEEDED,
          new TerminalResult(TerminalStatus.SUCCEEDED, false, Map.of()), Instant.now()))
          .isEqualTo(OperationCompletion.ALREADY_TERMINAL);
      assertThat(coordinator.findByCorrelation(stop.correlationId()).orElseThrow().terminal()).isFalse();
      assertThat(swarm.getWorkloadIntent()).isEqualTo(WorkloadIntent.STOPPED);
    } finally {
      releaseStart.countDown();
    }
  }
}
