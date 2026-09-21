package io.pockethive.swarmcontroller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.ControlSignal;
import io.pockethive.controlplane.ControlPlaneSignals;
import io.pockethive.swarm.model.lifecycle.Target;
import io.pockethive.swarm.model.lifecycle.TerminalStatus;
import io.pockethive.swarm.model.lifecycle.WorkloadState;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Responsibility: Execute START/STOP commands and publish their result after canonical worker convergence.
 * Must not: Decode transport messages, apply config updates, or own lifecycle/readiness domain state.
 * Contract: Admit one command before mutation; success requires fresh matching worker observations and bounded
 * timeout reports exact non-convergence.
 */
final class SwarmLifecycleCommandHandler {

  private static final Logger log = LoggerFactory.getLogger(SwarmLifecycleCommandHandler.class);
  private static final long CONVERGENCE_TIMEOUT_NANOS = Duration.ofSeconds(30).toNanos();

  private final SwarmLifecycle lifecycle;
  private final ObjectMapper mapper;
  private final SwarmCommandReadiness readiness;
  private final SwarmControllerResultPublisher results;
  private final SwarmStatusFullCoordinator statusFullCoordinator;
  private final LongSupplier nanoTime;
  private PendingLifecycle pendingLifecycle;

  SwarmLifecycleCommandHandler(
      SwarmLifecycle lifecycle,
      ObjectMapper mapper,
      SwarmCommandReadiness readiness,
      SwarmControllerResultPublisher results,
      SwarmStatusFullCoordinator statusFullCoordinator) {
    this(lifecycle, mapper, readiness, results, statusFullCoordinator, System::nanoTime);
  }

  SwarmLifecycleCommandHandler(
      SwarmLifecycle lifecycle,
      ObjectMapper mapper,
      SwarmCommandReadiness readiness,
      SwarmControllerResultPublisher results,
      SwarmStatusFullCoordinator statusFullCoordinator,
      LongSupplier nanoTime) {
    this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    this.mapper = Objects.requireNonNull(mapper, "mapper");
    this.readiness = Objects.requireNonNull(readiness, "readiness");
    this.results = Objects.requireNonNull(results, "results");
    this.statusFullCoordinator = Objects.requireNonNull(statusFullCoordinator, "statusFullCoordinator");
    this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
  }

  synchronized void handle(ControlSignal signal, String operation, String swarmId) {
    Objects.requireNonNull(signal, "signal");
    WorkloadState requestedState = requestedState(operation);
    if (pendingLifecycle != null) {
      IllegalStateException failure =
          new IllegalStateException("Another lifecycle command is awaiting convergence");
      log.warn(phase(operation), failure);
      results.publishFailure(signal, operation, failure);
      return;
    }
    SwarmCommandReadinessSnapshot readinessSnapshot = readiness.snapshot();
    if (!readinessSnapshot.accepts(false)) {
      log.warn(
          "[CTRL] command rejected operation={} phase={} code={} message={} swarmId={} correlationId={} "
              + "idempotencyKey={} retryable={} initialized={} ready={} pendingConfigUpdates={} status={}",
          operation,
          phase(operation),
          "not-ready",
          "Swarm controller is not ready for this operation",
          swarmId,
          signal.correlationId(),
          signal.idempotencyKey(),
          true,
          readinessSnapshot.initialized(),
          readinessSnapshot.ready(),
          readinessSnapshot.pendingConfigUpdates(),
          readinessSnapshot.workloadState() == null ? "unknown" : readinessSnapshot.workloadState().name());
      results.publishLifecycle(signal, operation, TerminalStatus.REJECTED, List.of());
      return;
    }
    if (lifecycle.getWorkloadState() == requestedState) {
      log.info(
          "Lifecycle command already achieved operation={} swarmId={} workloadState={} correlationId={} "
              + "idempotencyKey={}",
          operation, swarmId, requestedState, signal.correlationId(), signal.idempotencyKey());
      results.publishLifecycle(signal, operation, TerminalStatus.SUCCEEDED, List.of());
      return;
    }

    String label = phase(operation);
    try {
      long startedAtNanos = nanoTime.getAsLong();
      log.info("{} signal for swarm {}", capitalize(label), swarmId);
      String args = serializeArgs(signal);
      if (ControlPlaneSignals.SWARM_START.equals(operation)) {
        lifecycle.start(args);
      } else {
        lifecycle.stop();
      }
      long observationRevision = lifecycle.workerStatusObservationRevision();
      PendingLifecycle next = new PendingLifecycle(
          signal,
          operation,
          observationRevision,
          ControlPlaneSignals.SWARM_START.equals(operation),
          startedAtNanos);
      pendingLifecycle = next;
    } catch (Exception failure) {
      log.warn(label, failure);
      results.publishFailure(signal, operation, failure);
      return;
    }
    tryComplete();
  }

  synchronized void tryComplete() {
    PendingLifecycle pending = pendingLifecycle;
    if (pending == null) {
      return;
    }
    List<Target> nonConverged = lifecycle.nonConvergedWorkersAfter(
        pending.observationRevision(), pending.expectedEnabled());
    boolean blocked = pending.expectedEnabled()
        && (lifecycle.hasPendingConfigUpdates() || !lifecycle.isReadyForWork());
    boolean timedOut = nanoTime.getAsLong() - pending.startedAtNanos() >= CONVERGENCE_TIMEOUT_NANOS;
    if ((blocked || !nonConverged.isEmpty()) && !timedOut) {
      return;
    }
    pendingLifecycle = null;
    TerminalStatus status = blocked || !nonConverged.isEmpty()
        ? TerminalStatus.FAILED
        : TerminalStatus.SUCCEEDED;
    results.publishLifecycle(pending.signal(), pending.operation(), status, nonConverged);
    statusFullCoordinator.queueAfterLifecycle(pending.observationRevision());
  }

  synchronized void failPending(String reason) {
    PendingLifecycle pending = pendingLifecycle;
    pendingLifecycle = null;
    lifecycle.fail(reason);
    if (pending == null) {
      log.warn("Config-update error received with no pending lifecycle command. reason={}", reason);
      return;
    }
    results.publishFailure(
        pending.signal(), pending.operation(), new IllegalStateException(reason));
  }

  private String serializeArgs(ControlSignal signal) {
    Map<String, Object> args = signal.data();
    if (args == null || args.isEmpty()) {
      return "{}";
    }
    try {
      return mapper.writeValueAsString(args);
    } catch (Exception failure) {
      throw new IllegalStateException("Unable to serialize control signal args", failure);
    }
  }

  private static WorkloadState requestedState(String operation) {
    if (ControlPlaneSignals.SWARM_START.equals(operation)) {
      return WorkloadState.RUNNING;
    }
    if (ControlPlaneSignals.SWARM_STOP.equals(operation)) {
      return WorkloadState.STOPPED;
    }
    throw new IllegalArgumentException("Unsupported lifecycle operation: " + operation);
  }

  private static String phase(String operation) {
    return ControlPlaneSignals.SWARM_START.equals(operation) ? "start" : "stop";
  }

  private static String capitalize(String value) {
    return value.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + value.substring(1);
  }

  private record PendingLifecycle(
      ControlSignal signal,
      String operation,
      long observationRevision,
      boolean expectedEnabled,
      long startedAtNanos) {
  }
}
