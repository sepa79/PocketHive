package io.pockethive.swarmcontroller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.ControlScope;
import io.pockethive.control.ControlSignal;
import io.pockethive.controlplane.CanonicalPayloadDigest;
import io.pockethive.controlplane.ControlPlaneSignals;
import io.pockethive.controlplane.messaging.ControlPlaneEmitter;
import io.pockethive.swarm.model.lifecycle.Target;
import io.pockethive.swarm.model.lifecycle.TerminalResult;
import io.pockethive.swarm.model.lifecycle.TerminalStatus;
import io.pockethive.swarm.model.lifecycle.WorkloadState;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Responsibility: Construct and publish canonical Swarm Controller command results and failures.
 * Must not: Execute commands, decide convergence, or mutate lifecycle state.
 * Contract: Result evidence reflects the lifecycle owner at the moment the result is published.
 */
final class SwarmControllerResultPublisher {

  private final SwarmLifecycle lifecycle;
  private final ObjectMapper mapper;
  private final ControlPlaneEmitter emitter;
  private final String controllerRole;
  private final String controllerInstance;
  private final Clock clock;

  SwarmControllerResultPublisher(
      SwarmLifecycle lifecycle,
      ObjectMapper mapper,
      ControlPlaneEmitter emitter,
      String controllerRole,
      String controllerInstance) {
    this(lifecycle, mapper, emitter, controllerRole, controllerInstance, Clock.systemUTC());
  }

  SwarmControllerResultPublisher(
      SwarmLifecycle lifecycle,
      ObjectMapper mapper,
      ControlPlaneEmitter emitter,
      String controllerRole,
      String controllerInstance,
      Clock clock) {
    this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    this.mapper = Objects.requireNonNull(mapper, "mapper");
    this.emitter = Objects.requireNonNull(emitter, "emitter");
    this.controllerRole = requireText(controllerRole, "controllerRole");
    this.controllerInstance = requireText(controllerInstance, "controllerInstance");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  void publishLifecycle(
      ControlSignal signal,
      String operation,
      TerminalStatus status,
      List<Target> nonConvergedWorkers) {
    requireLifecycleOperation(operation);
    publishResult(signal, operation, lifecycleResult(signal, operation, status, nonConvergedWorkers));
  }

  void publishConfig(
      ControlSignal signal,
      TerminalStatus status) {
    publishResult(signal, ControlPlaneSignals.CONFIG_UPDATE, configResult(signal, status));
  }

  void publishFailure(ControlSignal signal, String operation, Exception failure) {
    Objects.requireNonNull(failure, "failure");
    TerminalResult result = ControlPlaneSignals.CONFIG_UPDATE.equals(operation)
        ? configResult(signal, TerminalStatus.FAILED)
        : lifecycleResult(signal, requireLifecycleOperation(operation), TerminalStatus.FAILED, List.of());
    String code = failure.getClass().getSimpleName();
    String message = failure.getMessage() == null || failure.getMessage().isBlank()
        ? code
        : failure.getMessage();
    emitter.emitFailure(new ControlPlaneEmitter.FailureContext(
        operation,
        signal.correlationId(),
        signal.idempotencyKey(),
        result,
        phase(operation),
        code,
        message,
        failure.getClass().getName(),
        failure.getMessage(),
        null,
        clock.instant()));
  }

  private void publishResult(ControlSignal signal, String operation, TerminalResult result) {
    emitter.emitResult(new ControlPlaneEmitter.ResultContext(
        operation, signal.correlationId(), signal.idempotencyKey(), result, clock.instant()));
  }

  private TerminalResult lifecycleResult(
      ControlSignal signal,
      String operation,
      TerminalStatus status,
      List<Target> nonConvergedWorkers) {
    WorkloadState current = lifecycle.getWorkloadState();
    String observed = current == null ? WorkloadState.UNKNOWN.name() : current.name();
    Map<String, Object> context = new LinkedHashMap<>();
    context.put("target", target(signal));
    context.put("requestedWorkloadState", requestedState(operation).name());
    context.put("observedWorkloadState", observed);
    context.put("nonConvergedWorkers",
        nonConvergedWorkers == null ? List.of() : List.copyOf(nonConvergedWorkers));
    return new TerminalResult(status, status == TerminalStatus.FAILED, context);
  }

  private TerminalResult configResult(
      ControlSignal signal,
      TerminalStatus status) {
    WorkloadState workloadState = lifecycle.getWorkloadState();
    boolean enabled = workloadState == WorkloadState.RUNNING || workloadState == WorkloadState.STARTING;
    Map<String, Object> context = new LinkedHashMap<>();
    context.put("target", target(signal));
    JsonNode data = mapper.valueToTree(signal.data());
    context.put("requestedEnabled", data.has("enabled") ? data.path("enabled").asBoolean() : null);
    context.put("observedEnabled", enabled);
    context.put("appliedConfigSha256",
        status == TerminalStatus.SUCCEEDED
            ? CanonicalPayloadDigest.sha256(mapper, signal.data())
            : null);
    return new TerminalResult(status, false, context);
  }

  private Target target(ControlSignal signal) {
    ControlScope scope = signal.scope();
    return new Target(
        scope == null || ControlScope.isAll(scope.role()) ? controllerRole : scope.role(),
        scope == null || ControlScope.isAll(scope.instance()) ? controllerInstance : scope.instance());
  }

  private static WorkloadState requestedState(String operation) {
    return ControlPlaneSignals.SWARM_START.equals(operation)
        ? WorkloadState.RUNNING
        : WorkloadState.STOPPED;
  }

  private static String requireLifecycleOperation(String operation) {
    if (!ControlPlaneSignals.SWARM_START.equals(operation)
        && !ControlPlaneSignals.SWARM_STOP.equals(operation)) {
      throw new IllegalArgumentException("Unsupported lifecycle operation: " + operation);
    }
    return operation;
  }

  private static String phase(String operation) {
    return ControlPlaneSignals.SWARM_START.equals(operation) ? "start"
        : ControlPlaneSignals.SWARM_STOP.equals(operation) ? "stop"
        : operation;
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value.trim();
  }
}
