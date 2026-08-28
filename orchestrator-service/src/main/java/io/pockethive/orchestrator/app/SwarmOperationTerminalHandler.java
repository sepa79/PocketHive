package io.pockethive.orchestrator.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.CommandResult;
import io.pockethive.controlplane.ControlPlaneOperations;
import io.pockethive.controlplane.ControlPlaneRoles;
import io.pockethive.controlplane.routing.ControlPlaneRouting.RoutingKey;
import io.pockethive.orchestrator.domain.HiveJournal;
import io.pockethive.orchestrator.domain.HiveJournal.HiveJournalEntry;
import io.pockethive.orchestrator.domain.OperationCompletion;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmOperationCoordinator;
import io.pockethive.orchestrator.domain.SwarmStore;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Responsibility: Submit matching executor evidence and timeout evidence to the operation coordinator.
 * Must not: Consume transport messages, verify REMOVE resources, publish status, or mutate swarm observations.
 * Contract: Publish an outcome only for terminal state accepted by the canonical operation coordinator.
 */
@Component
public class SwarmOperationTerminalHandler {

  private static final Logger log = LoggerFactory.getLogger(SwarmOperationTerminalHandler.class);
  private static final String CONTROLLER_ROLE = ControlPlaneRoles.SWARM_CONTROLLER;

  private final SwarmStore store;
  private final ObjectMapper json;
  private final HiveJournal hiveJournal;
  private final SwarmOperationCoordinator operations;
  private final OperationOutcomePublisher outcomes;
  private final SwarmOperationObservationHandler observations;
  private final SwarmRemovalConvergenceHandler removals;

  public SwarmOperationTerminalHandler(
      SwarmStore store,
      ObjectMapper json,
      HiveJournal hiveJournal,
      SwarmOperationCoordinator operations,
      OperationOutcomePublisher outcomes,
      SwarmOperationObservationHandler observations,
      SwarmRemovalConvergenceHandler removals) {
    this.store = Objects.requireNonNull(store, "store");
    this.json = Objects.requireNonNull(json, "json").findAndRegisterModules();
    this.hiveJournal = Objects.requireNonNull(hiveJournal, "hiveJournal");
    this.operations = Objects.requireNonNull(operations, "operations");
    this.outcomes = Objects.requireNonNull(outcomes, "outcomes");
    this.observations = Objects.requireNonNull(observations, "observations");
    this.removals = Objects.requireNonNull(removals, "removals");
  }

  void accept(RoutingKey key, String routingKey, CommandResult result) {
    String signal = key.type().substring((CommandResult.KIND + ".").length());
    OperationType operationType = ControlPlaneOperations.typeForSignal(signal);
    if (operations.findByCorrelation(result.correlationId()).isEmpty()) {
      log.debug(
          "Ignoring executor result with no Orchestrator-owned operation signal={} swarm={} role={} instance={} correlation={}",
          signal, key.swarmId(), key.role(), key.instance(), result.correlationId());
      return;
    }
    requireTerminalTargetMatchesEnvelope(key, result);
    if (operationType == OperationType.REMOVE) {
      throw new IllegalArgumentException("swarm-remove terminal evidence must come from the filesystem");
    }
    if (operationType == OperationType.CONFIG_UPDATE
        && result.data().status() == TerminalStatus.SUCCEEDED
        && observations.awaitConfigObservation(result)) {
      OperationCompletion completion = operations.findByCorrelation(result.correlationId())
          .filter(SwarmOperation::terminal)
          .map(ignored -> OperationCompletion.COMPLETED)
          .orElse(OperationCompletion.AWAITING_OBSERVATION);
      journalResult(key, routingKey, result, completion);
      return;
    }
    OperationCompletion completion = operations.recordResult(
        key.swarmId(), operationType, new Target(key.role(), key.instance()),
        result.correlationId(), result.idempotencyKey(),
        terminalState(result.data().status()), result.data(), result.timestamp());
    journalResult(key, routingKey, result, completion);
    if (completion != OperationCompletion.COMPLETED) {
      log.info("Ignoring result completion={} signal={} swarm={} correlation={}",
          completion, signal, key.swarmId(), result.correlationId());
      return;
    }
    SwarmOperation terminal = operations.findByCorrelation(result.correlationId()).orElseThrow();
    outcomes.publish(terminal);
  }

  @Scheduled(fixedRate = 2_000L)
  public void checkTimeouts() {
    removals.checkResults();
    operations.expire(Instant.now(), this::timeoutResult).forEach(operation -> {
      log.warn("Operation timed out type={} swarm={} correlation={}",
          operation.type(), operation.swarmId(), operation.correlationId());
      outcomes.publish(operation);
    });
    observations.discardTerminalOperations();
    operations.operations().stream()
        .filter(SwarmOperation::terminal)
        .filter(operation -> !outcomes.isPublished(operation.correlationId()))
        .forEach(outcomes::publish);
  }

  private TerminalResult timeoutResult(SwarmOperation operation) {
    Swarm swarm = store.find(operation.swarmId()).orElse(null);
    Target target = operation.target();
    Map<String, Object> context = new LinkedHashMap<>();
    context.put("target", target);
    switch (operation.type()) {
      case CREATE -> {
        context.put("runtimeIntent", "PRESENT");
        context.put("controllerState", swarm == null ? "UNKNOWN" : swarm.getControllerState().name());
        context.put("workloadState", swarm == null ? "UNKNOWN" : swarm.getWorkloadState().name());
        context.put("startupArtifactSha256", swarm == null || swarm.startupArtifact() == null
            ? "missing" : swarm.startupArtifact().sha256());
      }
      case START, STOP -> {
        context.put("requestedWorkloadState", operation.type() == OperationType.START ? "RUNNING" : "STOPPED");
        context.put("observedWorkloadState", swarm == null ? "UNKNOWN" : swarm.getWorkloadState().name());
        context.put("nonConvergedWorkers", List.of());
      }
      case CONFIG_UPDATE -> {
        Boolean requestedEnabled = operations.configExpectation(operation.correlationId())
            .map(SwarmOperationCoordinator.ConfigUpdateExpectation::enabledExpectation)
            .filter(SwarmOperationCoordinator.ConfigEnabledExpectation::requiresObservation)
            .map(SwarmOperationCoordinator.ConfigEnabledExpectation::requestedEnabled)
            .orElse(null);
        context.put("requestedEnabled", requestedEnabled);
        context.put("observedEnabled", observedEnabled(swarm, operation.target()));
        context.put("appliedConfigSha256", null);
      }
      case REMOVE -> {
        context.put("removedResources", List.of());
        context.put("remainingResources", List.of());
        context.put("errors", List.of(Map.of("code", "timeout", "message", "remove result not written")));
      }
    }
    return new TerminalResult(TerminalStatus.TIMED_OUT, true, context);
  }

  private static Boolean observedEnabled(Swarm swarm, Target target) {
    if (swarm == null) {
      return null;
    }
    if (CONTROLLER_ROLE.equals(target.role()) && target.instance().equals(swarm.getInstanceId())) {
      return switch (swarm.getWorkloadState()) {
        case RUNNING, STARTING -> true;
        case STOPPED, STOPPING -> false;
        case UNAVAILABLE, UNKNOWN -> null;
      };
    }
    Object workers = swarm.getObservation().get("workers");
    if (!(workers instanceof List<?> workerList)) {
      return null;
    }
    for (Object item : workerList) {
      if (item instanceof Map<?, ?> worker
          && target.role().equals(worker.get("role"))
          && target.instance().equals(worker.get("instance"))
          && worker.get("enabled") instanceof Boolean enabled) {
        return enabled;
      }
    }
    return null;
  }

  private void journalResult(
      RoutingKey key, String routingKey, CommandResult result, OperationCompletion completion) {
    try {
      hiveJournal.append(HiveJournalEntry.info(
          key.swarmId(), HiveJournal.Direction.IN, CommandResult.KIND, result.type(), result.origin(), result.scope(),
          result.correlationId(), result.idempotencyKey(), routingKey,
          Map.of("status", result.data().status().wireValue(), "completion", completion.name()),
          null, null));
    } catch (Exception ignored) {
      log.debug("Unable to journal executor result", ignored);
    }
  }

  private void requireTerminalTargetMatchesEnvelope(RoutingKey key, CommandResult result) {
    Object rawTarget = result.data().context().get("target");
    if (rawTarget == null) {
      throw new IllegalArgumentException("Result terminal context.target is required");
    }
    Target target = json.convertValue(rawTarget, Target.class);
    if (!key.role().equals(target.role()) || !key.instance().equals(target.instance())) {
      throw new IllegalArgumentException("Result terminal target does not match its routing key");
    }
  }

  private static OperationState terminalState(TerminalStatus status) {
    return switch (status) {
      case SUCCEEDED -> OperationState.SUCCEEDED;
      case REJECTED -> OperationState.REJECTED;
      case FAILED -> OperationState.FAILED;
      case TIMED_OUT -> OperationState.TIMED_OUT;
    };
  }
}
