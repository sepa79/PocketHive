package io.pockethive.orchestrator.app;

import com.fasterxml.jackson.databind.JsonNode;
import io.pockethive.control.CommandResult;
import io.pockethive.controlplane.ControlPlaneRoles;
import io.pockethive.orchestrator.domain.OperationCompletion;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmOperationCoordinator;
import io.pockethive.orchestrator.domain.SwarmStore;
import io.pockethive.swarm.model.lifecycle.ControllerState;
import io.pockethive.swarm.model.lifecycle.OperationState;
import io.pockethive.swarm.model.lifecycle.OperationType;
import io.pockethive.swarm.model.lifecycle.SwarmOperation;
import io.pockethive.swarm.model.lifecycle.Target;
import io.pockethive.swarm.model.lifecycle.TerminalResult;
import io.pockethive.swarm.model.lifecycle.TerminalStatus;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Responsibility: Complete CREATE and config-update operations from fresh controller observations.
 * Must not: Consume transport messages, publish status, or handle removal and timeout convergence.
 * Contract: Publish an outcome only after the operation coordinator accepts matching terminal evidence.
 */
@Component
public class SwarmOperationObservationHandler {

  private static final String CONTROLLER_ROLE = ControlPlaneRoles.SWARM_CONTROLLER;

  private final SwarmStore store;
  private final SwarmOperationCoordinator operations;
  private final OperationOutcomePublisher outcomes;
  private final Map<String, CommandResult> pendingConfigResults = new ConcurrentHashMap<>();

  public SwarmOperationObservationHandler(
      SwarmStore store,
      SwarmOperationCoordinator operations,
      OperationOutcomePublisher outcomes) {
    this.store = Objects.requireNonNull(store, "store");
    this.operations = Objects.requireNonNull(operations, "operations");
    this.outcomes = Objects.requireNonNull(outcomes, "outcomes");
  }

  boolean awaitConfigObservation(CommandResult result) {
    SwarmOperation operation = operations.findByCorrelation(result.correlationId())
        .orElseThrow(() -> new IllegalArgumentException("Result has no matching operation"));
    var expectation = operations.configExpectation(result.correlationId())
        .orElseThrow(() -> new IllegalArgumentException("CONFIG_UPDATE operation has no request expectation"));
    var enabledExpectation = expectation.enabledExpectation();
    if (!enabledExpectation.requiresObservation()) {
      return false;
    }
    boolean requestedEnabled = enabledExpectation.requestedEnabled();
    Object executorRequested = result.data().context().get("requestedEnabled");
    if (!(executorRequested instanceof Boolean value) || !value.equals(requestedEnabled)) {
      throw new IllegalArgumentException("Executor result requestedEnabled does not match the operation request");
    }
    pendingConfigResults.put(result.correlationId(), result);
    completePendingConfigUpdates(operation.swarmId());
    return true;
  }

  void handleControllerStatusFull(
      String swarmId, String controllerInstance, JsonNode statusEnvelope) {
    completePendingConfigUpdates(swarmId);
    SwarmOperation operation = operations.activeLifecycle(swarmId)
        .filter(candidate -> candidate.type() == OperationType.CREATE)
        .orElse(null);
    if (operation == null) {
      return;
    }
    JsonNode context = statusEnvelope.path("data").path("context");
    if (!context.path("startupReady").asBoolean(false)) {
      return;
    }
    String reportedDigest = context.path("startupArtifactSha256").asText(null);
    String expectedDigest = store.find(swarmId)
        .map(Swarm::startupArtifact)
        .map(reference -> reference.sha256())
        .orElse(null);
    ControllerState controllerState = enumValue(
        ControllerState.class, context.path("controllerState").asText(null), ControllerState.UNKNOWN);
    boolean ready = controllerState == ControllerState.READY
        && "STOPPED".equals(context.path("workloadState").asText(null))
        && Objects.equals(expectedDigest, reportedDigest);
    TerminalStatus status = ready ? TerminalStatus.SUCCEEDED : TerminalStatus.FAILED;
    Map<String, Object> terminalContext = new LinkedHashMap<>();
    terminalContext.put("target", new Target(CONTROLLER_ROLE, controllerInstance));
    terminalContext.put("runtimeIntent", "PRESENT");
    terminalContext.put("controllerState", controllerState.name());
    terminalContext.put("workloadState", context.path("workloadState").asText("UNKNOWN"));
    terminalContext.put("startupArtifactSha256", Objects.toString(reportedDigest, "missing"));
    TerminalResult result = new TerminalResult(status, false, terminalContext);
    OperationCompletion completion = operations.recordResult(
        swarmId, OperationType.CREATE, operation.target(),
        operation.correlationId(), operation.idempotencyKey(),
        ready ? OperationState.SUCCEEDED : OperationState.FAILED, result, Instant.now());
    if (completion == OperationCompletion.COMPLETED) {
      outcomes.publish(operations.findByCorrelation(operation.correlationId()).orElseThrow());
    }
  }

  void handleControllerObservation(String swarmId) {
    completePendingConfigUpdates(swarmId);
  }

  void discardTerminalOperations() {
    pendingConfigResults.keySet().removeIf(correlationId ->
        operations.findByCorrelation(correlationId).map(SwarmOperation::terminal).orElse(true));
  }

  private void completePendingConfigUpdates(String swarmId) {
    if (swarmId == null || swarmId.isBlank()) {
      return;
    }
    for (var entry : List.copyOf(pendingConfigResults.entrySet())) {
      String correlationId = entry.getKey();
      CommandResult pending = entry.getValue();
      SwarmOperation operation = operations.findByCorrelation(correlationId).orElse(null);
      if (operation == null || operation.terminal()) {
        pendingConfigResults.remove(correlationId, pending);
        continue;
      }
      if (!operation.swarmId().equals(swarmId) || !hasFreshMatchingObservation(operation)) {
        continue;
      }
      if (!pendingConfigResults.remove(correlationId, pending)) {
        continue;
      }
      OperationCompletion completion = operations.recordResult(
          operation.swarmId(), operation.type(), operation.target(),
          operation.correlationId(), operation.idempotencyKey(),
          OperationState.SUCCEEDED, pending.data(), Instant.now());
      if (completion == OperationCompletion.COMPLETED) {
        SwarmOperation terminal = operations.findByCorrelation(correlationId).orElseThrow();
        outcomes.publish(terminal);
      }
    }
  }

  private boolean hasFreshMatchingObservation(SwarmOperation operation) {
    Swarm swarm = store.find(operation.swarmId()).orElse(null);
    if (swarm == null || operation.dispatchedAt() == null) {
      return false;
    }
    var enabledExpectation = operations.configExpectation(operation.correlationId())
        .map(SwarmOperationCoordinator.ConfigUpdateExpectation::enabledExpectation)
        .orElse(SwarmOperationCoordinator.ConfigEnabledExpectation.UNCHANGED);
    if (!enabledExpectation.requiresObservation()) {
      return true;
    }
    boolean requestedEnabled = enabledExpectation.requestedEnabled();
    if (CONTROLLER_ROLE.equals(operation.target().role())
        && operation.target().instance().equals(swarm.getInstanceId())) {
      Instant observedAt = swarm.getControllerStatusReceivedAt();
      return observedAt != null && !observedAt.isBefore(operation.dispatchedAt())
          && swarm.getWorkloadState() == (requestedEnabled
              ? io.pockethive.swarm.model.lifecycle.WorkloadState.RUNNING
              : io.pockethive.swarm.model.lifecycle.WorkloadState.STOPPED);
    }
    Object workers = swarm.getObservation().get("workers");
    if (!(workers instanceof List<?> workerList)) {
      return false;
    }
    for (Object item : workerList) {
      if (!(item instanceof Map<?, ?> worker)) {
        continue;
      }
      if (!operation.target().role().equals(worker.get("role"))
          || !operation.target().instance().equals(worker.get("instance"))
          || !Boolean.valueOf(requestedEnabled).equals(worker.get("enabled"))) {
        continue;
      }
      Object rawLastSeenAt = worker.get("lastSeenAt");
      try {
        return rawLastSeenAt instanceof String value
            && !Instant.parse(value).isBefore(operation.dispatchedAt());
      } catch (java.time.format.DateTimeParseException ignored) {
        return false;
      }
    }
    return false;
  }

  private static <E extends Enum<E>> E enumValue(Class<E> type, String value, E missing) {
    if (value == null || value.isBlank()) {
      return missing;
    }
    try {
      return Enum.valueOf(type, value.trim().toUpperCase());
    } catch (IllegalArgumentException ignored) {
      return missing;
    }
  }
}
