package io.pockethive.swarmcontroller;

import static io.pockethive.swarmcontroller.SwarmControllerTestProperties.TEST_SWARM_ID;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.ControlSignal;
import io.pockethive.controlplane.ControlPlaneSignals;
import io.pockethive.swarm.model.lifecycle.Target;
import io.pockethive.swarm.model.lifecycle.TerminalStatus;
import io.pockethive.swarm.model.lifecycle.WorkloadState;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SwarmLifecycleCommandHandlerTest {

  private static final long START_REVISION = 41L;
  private static final Target WORKER = new Target("generator", "generator-1");

  @Mock
  private SwarmLifecycle lifecycle;

  @Mock
  private SwarmCommandReadiness readiness;

  @Mock
  private SwarmControllerResultPublisher results;

  @Mock
  private SwarmStatusFullCoordinator statusFullCoordinator;

  private MutableTicker ticker;
  private SwarmLifecycleCommandHandler handler;

  @BeforeEach
  void setUp() {
    ticker = new MutableTicker();
    handler = new SwarmLifecycleCommandHandler(
        lifecycle,
        new ObjectMapper().findAndRegisterModules(),
        readiness,
        results,
        statusFullCoordinator,
        ticker);
    when(readiness.snapshot()).thenReturn(
        new SwarmCommandReadinessSnapshot(true, true, false, WorkloadState.STOPPED));
    org.mockito.Mockito.lenient().when(lifecycle.isReadyForWork()).thenReturn(true);
    org.mockito.Mockito.lenient().when(lifecycle.workerStatusObservationRevision()).thenReturn(START_REVISION);
  }

  @Test
  void startsAndPublishesSuccessOnlyAfterFreshWorkerConvergence() throws Exception {
    ControlSignal signal = signal(ControlPlaneSignals.SWARM_START, Map.of("rate", 7));
    when(lifecycle.getWorkloadState()).thenReturn(WorkloadState.STOPPED);
    when(lifecycle.nonConvergedWorkersAfter(START_REVISION, true))
        .thenReturn(List.of(WORKER), List.of());

    handler.handle(signal, ControlPlaneSignals.SWARM_START, TEST_SWARM_ID);

    verify(lifecycle).start("{\"rate\":7}");
    verify(results, never()).publishLifecycle(
        signal, ControlPlaneSignals.SWARM_START, TerminalStatus.SUCCEEDED, List.of());

    handler.tryComplete();

    verify(results).publishLifecycle(
        signal, ControlPlaneSignals.SWARM_START, TerminalStatus.SUCCEEDED, List.of());
    verify(statusFullCoordinator).queueAfterLifecycle(START_REVISION);
  }

  @Test
  void rejectsStopWithoutMutationWhileStartAwaitsConvergence() {
    ControlSignal start = signal(ControlPlaneSignals.SWARM_START, "correlation-start", "idempotency-start");
    ControlSignal stop = signal(ControlPlaneSignals.SWARM_STOP, "correlation-stop", "idempotency-stop");
    when(lifecycle.getWorkloadState()).thenReturn(WorkloadState.STOPPED);
    when(lifecycle.nonConvergedWorkersAfter(START_REVISION, true)).thenReturn(List.of(WORKER));

    handler.handle(start, ControlPlaneSignals.SWARM_START, TEST_SWARM_ID);
    handler.handle(stop, ControlPlaneSignals.SWARM_STOP, TEST_SWARM_ID);

    verify(lifecycle, never()).stop();
    verify(results).publishFailure(
        eq(stop),
        eq(ControlPlaneSignals.SWARM_STOP),
        argThat(failure -> failure instanceof IllegalStateException
            && failure.getMessage().contains("awaiting convergence")));
  }

  @Test
  void doesNotReportSecondStartAsSucceededWhileFirstAwaitsConvergence() {
    ControlSignal first = signal(ControlPlaneSignals.SWARM_START, "correlation-1", "idempotency-1");
    ControlSignal second = signal(ControlPlaneSignals.SWARM_START, "correlation-2", "idempotency-2");
    when(lifecycle.getWorkloadState()).thenReturn(WorkloadState.STOPPED);
    when(lifecycle.nonConvergedWorkersAfter(START_REVISION, true)).thenReturn(List.of(WORKER));

    handler.handle(first, ControlPlaneSignals.SWARM_START, TEST_SWARM_ID);
    handler.handle(second, ControlPlaneSignals.SWARM_START, TEST_SWARM_ID);

    verify(lifecycle).start("{}");
    verify(results, never()).publishLifecycle(
        second, ControlPlaneSignals.SWARM_START, TerminalStatus.SUCCEEDED, List.of());
    verify(results).publishFailure(
        eq(second),
        eq(ControlPlaneSignals.SWARM_START),
        argThat(failure -> failure instanceof IllegalStateException
            && failure.getMessage().contains("awaiting convergence")));
  }

  @Test
  void reportsExactNonConvergedWorkersAfterBoundedTimeout() {
    ControlSignal signal = signal(ControlPlaneSignals.SWARM_START, Map.of());
    when(lifecycle.getWorkloadState()).thenReturn(WorkloadState.STOPPED);
    when(lifecycle.nonConvergedWorkersAfter(START_REVISION, true)).thenReturn(List.of(WORKER));

    handler.handle(signal, ControlPlaneSignals.SWARM_START, TEST_SWARM_ID);
    ticker.advance(Duration.ofSeconds(30));
    handler.tryComplete();

    verify(results).publishLifecycle(
        signal, ControlPlaneSignals.SWARM_START, TerminalStatus.FAILED, List.of(WORKER));
    verify(statusFullCoordinator).queueAfterLifecycle(START_REVISION);
  }

  @Test
  void rejectsCommandBeforeMutatingLifecycleWhenControllerIsNotReady() {
    ControlSignal signal = signal(ControlPlaneSignals.SWARM_START, Map.of());
    when(readiness.snapshot()).thenReturn(
        new SwarmCommandReadinessSnapshot(false, false, false, WorkloadState.UNKNOWN));

    handler.handle(signal, ControlPlaneSignals.SWARM_START, TEST_SWARM_ID);

    verify(lifecycle, never()).start(org.mockito.ArgumentMatchers.anyString());
    verify(lifecycle, never()).stop();
    verify(results).publishLifecycle(
        signal, ControlPlaneSignals.SWARM_START, TerminalStatus.REJECTED, List.of());
  }

  @Test
  void confirmsAlreadyAchievedStateWithoutRebroadcastingCommand() {
    ControlSignal signal = signal(ControlPlaneSignals.SWARM_START, Map.of());
    when(lifecycle.getWorkloadState()).thenReturn(WorkloadState.RUNNING);

    handler.handle(signal, ControlPlaneSignals.SWARM_START, TEST_SWARM_ID);

    verify(lifecycle, never()).start(org.mockito.ArgumentMatchers.anyString());
    verify(results).publishLifecycle(
        signal, ControlPlaneSignals.SWARM_START, TerminalStatus.SUCCEEDED, List.of());
    verify(statusFullCoordinator, never()).queueAfterLifecycle(org.mockito.ArgumentMatchers.anyLong());
  }

  @Test
  void stopsAndWaitsForDisabledWorkerConvergence() {
    ControlSignal signal = signal(ControlPlaneSignals.SWARM_STOP, Map.of());
    when(lifecycle.getWorkloadState()).thenReturn(WorkloadState.RUNNING);
    when(lifecycle.nonConvergedWorkersAfter(START_REVISION, false)).thenReturn(List.of());

    handler.handle(signal, ControlPlaneSignals.SWARM_STOP, TEST_SWARM_ID);

    verify(lifecycle).stop();
    verify(results).publishLifecycle(
        signal, ControlPlaneSignals.SWARM_STOP, TerminalStatus.SUCCEEDED, List.of());
  }

  @Test
  void excludesStatusObservedDuringLifecycleMutationFromConvergence() {
    ControlSignal signal = signal(ControlPlaneSignals.SWARM_STOP, Map.of());
    AtomicLong revision = new AtomicLong(START_REVISION);
    when(lifecycle.getWorkloadState()).thenReturn(WorkloadState.RUNNING);
    when(lifecycle.workerStatusObservationRevision()).thenAnswer(ignored -> revision.get());
    doAnswer(ignored -> {
      revision.incrementAndGet();
      return null;
    }).when(lifecycle).stop();
    when(lifecycle.nonConvergedWorkersAfter(START_REVISION + 1, false))
        .thenReturn(List.of(WORKER), List.of());

    handler.handle(signal, ControlPlaneSignals.SWARM_STOP, TEST_SWARM_ID);

    verify(results, never()).publishLifecycle(
        signal, ControlPlaneSignals.SWARM_STOP, TerminalStatus.SUCCEEDED, List.of());

    handler.tryComplete();

    verify(results).publishLifecycle(
        signal, ControlPlaneSignals.SWARM_STOP, TerminalStatus.SUCCEEDED, List.of());
    verify(statusFullCoordinator).queueAfterLifecycle(START_REVISION + 1);
  }

  @Test
  void postResultStatusFailureIsNotTranslatedToSecondTerminalResult() {
    ControlSignal signal = signal(ControlPlaneSignals.SWARM_START, Map.of());
    IllegalStateException failure = new IllegalStateException("status unavailable");
    when(lifecycle.getWorkloadState()).thenReturn(WorkloadState.STOPPED);
    when(lifecycle.nonConvergedWorkersAfter(START_REVISION, true)).thenReturn(List.of());
    doThrow(failure).when(statusFullCoordinator).queueAfterLifecycle(START_REVISION);

    assertThatThrownBy(() -> handler.handle(
        signal, ControlPlaneSignals.SWARM_START, TEST_SWARM_ID)).isSameAs(failure);

    verify(results).publishLifecycle(
        signal, ControlPlaneSignals.SWARM_START, TerminalStatus.SUCCEEDED, List.of());
    verify(results, never()).publishFailure(
        eq(signal),
        eq(ControlPlaneSignals.SWARM_START),
        org.mockito.ArgumentMatchers.any(Exception.class));
  }

  @Test
  void configErrorFailsLifecycleAndItsPendingCommand() throws Exception {
    ControlSignal signal = signal(ControlPlaneSignals.SWARM_START, Map.of());
    when(lifecycle.getWorkloadState()).thenReturn(WorkloadState.STOPPED);
    when(lifecycle.nonConvergedWorkersAfter(START_REVISION, true)).thenReturn(List.of(WORKER));

    handler.handle(signal, ControlPlaneSignals.SWARM_START, TEST_SWARM_ID);
    handler.failPending("worker rejected config");

    verify(lifecycle).fail("worker rejected config");
    verify(results).publishFailure(
        eq(signal),
        eq(ControlPlaneSignals.SWARM_START),
        argThat(failure -> failure instanceof IllegalStateException
            && "worker rejected config".equals(failure.getMessage())));
  }

  private static ControlSignal signal(String operation, Map<String, Object> data) {
    return signal(operation, "correlation-1", "idempotency-1", data);
  }

  private static ControlSignal signal(String operation, String correlationId, String idempotencyKey) {
    return signal(operation, correlationId, idempotencyKey, Map.of());
  }

  private static ControlSignal signal(
      String operation,
      String correlationId,
      String idempotencyKey,
      Map<String, Object> data) {
    return ControlSignal.forInstance(
        operation,
        TEST_SWARM_ID,
        "swarm-controller",
        "controller-1",
        "orchestrator-1",
        correlationId,
        idempotencyKey,
        data);
  }

  private static final class MutableTicker implements LongSupplier {

    private long nanos;

    void advance(Duration duration) {
      nanos += duration.toNanos();
    }

    @Override
    public long getAsLong() {
      return nanos;
    }
  }
}
