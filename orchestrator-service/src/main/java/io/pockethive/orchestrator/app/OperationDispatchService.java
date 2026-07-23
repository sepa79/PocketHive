package io.pockethive.orchestrator.app;

import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmOperationCoordinator;
import io.pockethive.orchestrator.domain.SwarmStore;
import io.pockethive.swarm.model.lifecycle.OperationState;
import io.pockethive.swarm.model.lifecycle.OperationType;
import io.pockethive.swarm.model.lifecycle.Target;
import io.pockethive.swarm.model.lifecycle.TerminalResult;
import io.pockethive.swarm.model.lifecycle.TerminalStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Single application service for reserving and dispatching Orchestrator-owned operations. */
@Component
public final class OperationDispatchService {

  private static final Logger log = LoggerFactory.getLogger(OperationDispatchService.class);

  private final SwarmOperationCoordinator operations;
  private final OperationOutcomePublisher outcomes;
  private final SwarmStore swarms;

  public OperationDispatchService(
      SwarmOperationCoordinator operations,
      OperationOutcomePublisher outcomes,
      SwarmStore swarms) {
    this.operations = Objects.requireNonNull(operations, "operations");
    this.outcomes = Objects.requireNonNull(outcomes, "outcomes");
    this.swarms = Objects.requireNonNull(swarms, "swarms");
  }

  public SwarmOperationCoordinator.Reservation dispatch(
      String swarmId,
      OperationType type,
      Target target,
      String idempotencyKey,
      Duration timeout,
      Consumer<String> execution) {
    return dispatch(
        swarmId, type, target, UUID.randomUUID().toString(), idempotencyKey, timeout, execution);
  }

  public void registerConfigExpectation(
      String correlationId,
      SwarmOperationCoordinator.ConfigEnabledExpectation enabledExpectation) {
    operations.registerConfigExpectation(correlationId, enabledExpectation);
  }

  public SwarmOperationCoordinator.Reservation dispatch(
      String swarmId,
      OperationType type,
      Target target,
      String correlationId,
      String idempotencyKey,
      Duration timeout,
      Consumer<String> execution) {
    Objects.requireNonNull(timeout, "timeout");
    if (timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must be positive");
    }
    Objects.requireNonNull(execution, "execution");
    Instant now = Instant.now();
    var reservation = operations.reserve(
        swarmId, type, target, correlationId, idempotencyKey, now, now.plus(timeout));
    if (reservation.reused()) {
      return reservation;
    }
    var dispatched = operations.markDispatched(reservation.operation().correlationId(), Instant.now());
    try {
      execution.accept(reservation.operation().correlationId());
      return new SwarmOperationCoordinator.Reservation(
          operations.findByCorrelation(reservation.operation().correlationId()).orElse(dispatched), false);
    } catch (RuntimeException failure) {
      TerminalResult terminal = new TerminalResult(
          TerminalStatus.FAILED,
          true,
          failureContext(type, target, swarms.find(swarmId).orElse(null), failure));
      var completion = operations.recordResult(
          swarmId,
          type,
          target,
          correlationId,
          idempotencyKey,
          OperationState.FAILED,
          terminal,
          Instant.now());
      if (completion == io.pockethive.orchestrator.domain.OperationCompletion.COMPLETED) {
        try {
          operations.findByCorrelation(correlationId)
              .ifPresent(operation -> outcomes.publish(operation, runtimeMeta(swarmId)));
        } catch (RuntimeException publicationFailure) {
          if (publicationFailure != failure) {
            failure.addSuppressed(publicationFailure);
          }
          log.error(
              "Failed to publish terminal outcome type={} swarm={} correlation={}; "
                  + "the execution failure remains authoritative",
              type,
              swarmId,
              correlationId,
              publicationFailure);
        }
      }
      throw failure;
    }
  }

  private static Map<String, Object> failureContext(
      OperationType type,
      Target target,
      Swarm swarm,
      RuntimeException failure) {
    Map<String, Object> context = new LinkedHashMap<>();
    context.put("target", target);
    switch (type) {
      case CREATE -> {
        context.put("runtimeIntent", "PRESENT");
        context.put("controllerState", swarm == null ? "UNKNOWN" : swarm.getControllerState().name());
        context.put("workloadState", swarm == null ? "UNKNOWN" : swarm.getWorkloadState().name());
        context.put("startupArtifactSha256", swarm == null || swarm.startupArtifact() == null
            ? "missing" : swarm.startupArtifact().sha256());
      }
      case START, STOP -> {
        context.put("requestedWorkloadState", type == OperationType.START ? "RUNNING" : "STOPPED");
        context.put("observedWorkloadState", swarm == null ? "UNKNOWN" : swarm.getWorkloadState().name());
        context.put("nonConvergedWorkers", java.util.List.of());
      }
      case REMOVE -> {
        context.put("removedResources", java.util.List.of());
        context.put("remainingResources", java.util.List.of());
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", failure.getClass().getSimpleName());
        error.put("message", Objects.toString(failure.getMessage(), failure.getClass().getName()));
        error.put("resource", null);
        context.put("errors", java.util.List.of(java.util.Collections.unmodifiableMap(error)));
      }
      case CONFIG_UPDATE -> {
        context.put("requestedEnabled", null);
        context.put("observedEnabled", null);
        context.put("appliedConfigSha256", null);
      }
    }
    return context;
  }

  private Map<String, Object> runtimeMeta(String swarmId) {
    Swarm swarm = swarms.find(swarmId).orElse(null);
    if (swarm == null) {
      return Map.of();
    }
    Map<String, Object> runtime = new LinkedHashMap<>();
    if (swarm.templateId() != null) {
      runtime.put("templateId", swarm.templateId());
    }
    if (swarm.getRunId() != null) {
      runtime.put("runId", swarm.getRunId());
    }
    return Map.copyOf(runtime);
  }
}
