package io.pockethive.orchestrator.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.AlertMessage;
import io.pockethive.control.CommandResult;
import io.pockethive.control.JournalEvent;
import io.pockethive.control.ControlScope;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.ControlPlaneEventTypes;
import io.pockethive.controlplane.ControlPlaneOperations;
import io.pockethive.controlplane.ControlPlaneRoles;
import io.pockethive.controlplane.messaging.Alerts;
import io.pockethive.controlplane.messaging.ControlPlaneEmitter;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import io.pockethive.controlplane.routing.ControlPlaneRouting.RoutingKey;
import io.pockethive.controlplane.topology.ControlPlaneRouteCatalog;
import io.pockethive.controlplane.topology.ControlPlaneTopologyDescriptor;
import io.pockethive.orchestrator.domain.HiveJournal;
import io.pockethive.orchestrator.domain.HiveJournal.HiveJournalEntry;
import io.pockethive.orchestrator.domain.OperationCompletion;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmOperationCoordinator;
import io.pockethive.orchestrator.domain.SwarmStore;
import io.pockethive.orchestrator.runtime.RuntimeLogSnapshotJournalService;
import io.pockethive.orchestrator.runtime.RuntimeRemovalPostconditionVerifier;
import io.pockethive.controlplane.filesystem.FilesystemSwarmRemoveStore;
import io.pockethive.swarm.model.lifecycle.ControllerState;
import io.pockethive.swarm.model.lifecycle.OperationState;
import io.pockethive.swarm.model.lifecycle.OperationType;
import io.pockethive.swarm.model.lifecycle.SwarmOperation;
import io.pockethive.swarm.model.lifecycle.Target;
import io.pockethive.swarm.model.lifecycle.RemoveError;
import io.pockethive.swarm.model.lifecycle.RemoveResource;
import io.pockethive.swarm.model.lifecycle.RemoveResult;
import io.pockethive.swarm.model.lifecycle.TerminalResult;
import io.pockethive.swarm.model.lifecycle.TerminalStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Receives executor evidence; only the operation coordinator may turn it into a public outcome. */
@Component
@EnableScheduling
public class SwarmSignalListener {

  private static final Logger log = LoggerFactory.getLogger(SwarmSignalListener.class);
  private static final String ROLE = ControlPlaneRoles.ORCHESTRATOR;
  private static final String CONTROLLER_ROLE = ControlPlaneRoles.SWARM_CONTROLLER;
  private static final String OPERATION_TERMINAL_KIND = "operation-terminal";
  private static final long STATUS_INTERVAL_MS = 5_000L;

  private final SwarmStore store;
  private final ContainerLifecycleManager lifecycle;
  private final ObjectMapper json;
  private final HiveJournal hiveJournal;
  private final ControlPlaneJournalErrors journalErrors;
  private final ControlPlaneEmitter statusEmitter;
  private final RuntimeLogSnapshotJournalService runtimeLogSnapshots;
  private final SwarmOperationCoordinator operations;
  private final OperationOutcomePublisher outcomes;
  private final FilesystemSwarmRemoveStore removeStore;
  private final RuntimeRemovalPostconditionVerifier removalVerifier;
  private final String instanceId;
  private final String controlQueue;
  private final List<String> controlRoutes;
  private final Instant startedAt = Instant.now();
  private final Map<String, PendingConfigResult> pendingConfigResults = new ConcurrentHashMap<>();

  public SwarmSignalListener(
      SwarmStore store,
      ContainerLifecycleManager lifecycle,
      ObjectMapper json,
      HiveJournal hiveJournal,
      ControlPlaneEmitter statusEmitter,
      RuntimeLogSnapshotJournalService runtimeLogSnapshots,
      SwarmOperationCoordinator operations,
      OperationOutcomePublisher outcomes,
      FilesystemSwarmRemoveStore removeStore,
      RuntimeRemovalPostconditionVerifier removalVerifier,
      @Qualifier("managerControlPlaneIdentity") ControlPlaneIdentity identity,
      @Qualifier("managerControlPlaneTopologyDescriptor") ControlPlaneTopologyDescriptor descriptor,
      @Qualifier("managerControlQueueName") String controlQueue) {
    this.store = Objects.requireNonNull(store, "store");
    this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    this.json = Objects.requireNonNull(json, "json").findAndRegisterModules();
    this.hiveJournal = Objects.requireNonNull(hiveJournal, "hiveJournal");
    this.statusEmitter = Objects.requireNonNull(statusEmitter, "statusEmitter");
    this.runtimeLogSnapshots = Objects.requireNonNull(runtimeLogSnapshots, "runtimeLogSnapshots");
    this.operations = Objects.requireNonNull(operations, "operations");
    this.outcomes = Objects.requireNonNull(outcomes, "outcomes");
    this.removeStore = Objects.requireNonNull(removeStore, "removeStore");
    this.removalVerifier = Objects.requireNonNull(removalVerifier, "removalVerifier");
    this.instanceId = Objects.requireNonNull(identity, "identity").instanceId();
    this.controlQueue = requireText("controlQueue", controlQueue);
    this.controlRoutes = resolveControlRoutes(Objects.requireNonNull(descriptor, "descriptor").routes());
    this.journalErrors = new ControlPlaneJournalErrors(hiveJournal, ROLE, "swarm-signal-listener");
  }

  @RabbitListener(queues = "#{managerControlQueueName}")
  public void handle(String body, @Header(AmqpHeaders.RECEIVED_ROUTING_KEY) String routingKey) {
    try {
      RoutingKey key = requireEventKey(routingKey);
      if (key.type().startsWith("metric.status-")) {
        return;
      }
      if ("alert.alert".equals(key.type())) {
        runtimeLogSnapshots.captureForAlert(routingKey, json.readValue(body, AlertMessage.class));
        return;
      }
      if ("journal.work-journal".equals(key.type())) {
        acceptJournal(key, routingKey, body);
        return;
      }
      if (!key.type().startsWith("result.")) {
        log.warn("Dropping non-result terminal event rk={}", routingKey);
        journalDrop(key.swarmId(), routingKey, "expected event.result", body, null);
        return;
      }
      acceptResult(key, routingKey, body);
    } catch (Exception exception) {
      log.warn("Dropping invalid control-plane event rk={} payload={}", routingKey, snippet(body), exception);
      journalDrop(bestEffortSwarmId(routingKey), routingKey, "invalid control-plane event", body, exception);
    }
  }

  private void acceptJournal(RoutingKey key, String routingKey, String body) throws Exception {
    JournalEvent event = json.readValue(body, JournalEvent.class);
    if (!"work-journal".equals(event.type())
        || !key.swarmId().equals(event.scope().swarmId())
        || !key.role().equals(event.scope().role())
        || !key.instance().equals(event.scope().instance())) {
      throw new IllegalArgumentException("Journal envelope identity does not match its routing key");
    }
    hiveJournal.append(HiveJournalEntry.info(
        key.swarmId(), HiveJournal.Direction.IN, JournalEvent.KIND, event.type(), event.origin(), event.scope(),
        event.correlationId(), event.idempotencyKey(), routingKey, event.data(), null, null));
  }

  private void acceptResult(RoutingKey key, String routingKey, String body) throws Exception {
    CommandResult result = json.readValue(body, CommandResult.class);
    String signal = key.type().substring("result.".length());
    OperationType operationType = ControlPlaneOperations.typeForSignal(signal);
    requireRoutingMatchesEnvelope(key, result, signal);
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
        && awaitConfigObservation(key, result)) {
      OperationCompletion completion = operations.findByCorrelation(result.correlationId())
          .filter(SwarmOperation::terminal)
          .map(ignored -> OperationCompletion.COMPLETED)
          .orElse(OperationCompletion.AWAITING_OBSERVATION);
      journalResult(key, routingKey, result, completion);
      return;
    }
    OperationState terminalState = terminalState(result.data().status());
    OperationCompletion completion = operations.recordResult(
        key.swarmId(), operationType, new Target(key.role(), key.instance()),
        result.correlationId(), result.idempotencyKey(),
        terminalState, result.data(), result.timestamp());
    journalResult(key, routingKey, result, completion);
    if (completion != OperationCompletion.COMPLETED) {
      log.info("Ignoring result completion={} signal={} swarm={} correlation={}",
          completion, signal, key.swarmId(), result.correlationId());
      return;
    }
    SwarmOperation terminal = operations.findByCorrelation(result.correlationId()).orElseThrow();
    outcomes.publish(terminal, runtimeMeta(terminal.swarmId()));
  }

  void handleControllerStatusFull(String routingKey, JsonNode statusEnvelope) {
    RoutingKey key = ControlPlaneRouting.parseEvent(routingKey);
    if (key == null || !ControlPlaneEventTypes.METRIC_STATUS_FULL.equals(key.type())
        || !CONTROLLER_ROLE.equals(key.role())) {
      return;
    }
    completePendingConfigUpdates(key.swarmId());
    SwarmOperation operation = operations.activeLifecycle(key.swarmId())
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
    String expectedDigest = store.find(key.swarmId())
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
    terminalContext.put("target", new Target(CONTROLLER_ROLE, key.instance()));
    terminalContext.put("runtimeIntent", "PRESENT");
    terminalContext.put("controllerState", controllerState.name());
    terminalContext.put("workloadState", context.path("workloadState").asText("UNKNOWN"));
    terminalContext.put("startupArtifactSha256", Objects.toString(reportedDigest, "missing"));
    TerminalResult result = new TerminalResult(status, false, terminalContext);
    OperationCompletion completion = operations.recordResult(
        key.swarmId(), OperationType.CREATE, operation.target(),
        operation.correlationId(), operation.idempotencyKey(),
        terminalState(status), result, Instant.now());
    if (completion == OperationCompletion.COMPLETED) {
      outcomes.publish(operations.findByCorrelation(operation.correlationId()).orElseThrow(), runtimeMeta(key.swarmId()));
    }
  }

  void handleControllerObservation(String swarmId) {
    completePendingConfigUpdates(swarmId);
  }

  private boolean awaitConfigObservation(RoutingKey key, CommandResult result) {
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
    pendingConfigResults.put(result.correlationId(), new PendingConfigResult(key, result));
    completePendingConfigUpdates(operation.swarmId());
    return true;
  }

  private void completePendingConfigUpdates(String swarmId) {
    if (swarmId == null || swarmId.isBlank()) {
      return;
    }
    for (var entry : List.copyOf(pendingConfigResults.entrySet())) {
      String correlationId = entry.getKey();
      PendingConfigResult pending = entry.getValue();
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
      CommandResult result = pending.result();
      OperationCompletion completion = operations.recordResult(
          operation.swarmId(), operation.type(), operation.target(),
          operation.correlationId(), operation.idempotencyKey(),
          OperationState.SUCCEEDED, result.data(), Instant.now());
      if (completion == OperationCompletion.COMPLETED) {
        SwarmOperation terminal = operations.findByCorrelation(correlationId).orElseThrow();
        outcomes.publish(terminal, runtimeMeta(terminal.swarmId()));
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

  private record PendingConfigResult(RoutingKey key, CommandResult result) {
  }

  @Scheduled(fixedRate = 2_000L)
  public void checkTimeouts() {
    checkRemoveResults();
    operations.expire(Instant.now(), this::timeoutResult).forEach(operation -> {
      log.warn("Operation timed out type={} swarm={} correlation={}",
          operation.type(), operation.swarmId(), operation.correlationId());
      outcomes.publish(operation, runtimeMeta(operation.swarmId()));
    });
    pendingConfigResults.keySet().removeIf(correlationId ->
        operations.findByCorrelation(correlationId).map(SwarmOperation::terminal).orElse(true));
    operations.operations().stream()
        .filter(SwarmOperation::terminal)
        .filter(operation -> !outcomes.isPublished(operation.correlationId()))
        .forEach(operation -> outcomes.publish(operation, runtimeMeta(operation.swarmId())));
  }

  private void checkRemoveResults() {
    operations.operations().stream()
        .filter(operation -> operation.type() == OperationType.REMOVE && !operation.terminal())
        .forEach(operation -> {
          try {
            removeStore.findResult(operation.swarmId(), operation.correlationId())
                .ifPresent(result -> acceptRemoveResult(operation, result));
          } catch (RuntimeException failure) {
            failInvalidRemoveEvidence(operation, failure);
          }
        });
  }

  private void failInvalidRemoveEvidence(SwarmOperation operation, RuntimeException failure) {
    Swarm swarm = store.find(operation.swarmId()).orElse(null);
    RemoveResource controller = new RemoveResource(
        io.pockethive.swarm.model.lifecycle.RemoveResourceType.CONTROLLER_RUNTIME,
        swarm == null ? operation.target().instance() : swarm.getContainerId());
    TerminalResult terminal = new TerminalResult(
        TerminalStatus.FAILED,
        true,
        Map.of(
            "target", operation.target(),
            "removedResources", List.of(),
            "remainingResources", List.of(controller),
            "errors", List.of(new RemoveError(
                failure.getClass().getSimpleName(),
                Objects.toString(failure.getMessage(), failure.getClass().getName()),
                controller))));
    OperationCompletion completion = operations.recordResult(
        operation.swarmId(), OperationType.REMOVE, operation.target(),
        operation.correlationId(), operation.idempotencyKey(),
        OperationState.FAILED, terminal, Instant.now());
    if (completion == OperationCompletion.COMPLETED) {
      outcomes.publish(operations.findByCorrelation(operation.correlationId()).orElseThrow(),
          runtimeMeta(operation.swarmId()));
    }
  }

  private void acceptRemoveResult(SwarmOperation operation, RemoveResult result) {
    Swarm swarm = store.find(operation.swarmId()).orElseThrow();
    if (!result.swarmId().equals(operation.swarmId())
        || !result.runId().equals(swarm.getRunId())
        || !result.controllerInstance().equals(swarm.getInstanceId())
        || !result.correlationId().equals(operation.correlationId())
        || !result.idempotencyKey().equals(operation.idempotencyKey())) {
      throw new IllegalArgumentException("Remove result does not match the active operation identity");
    }
    Map<String, Object> runtime = runtimeMeta(operation.swarmId());
    List<RemoveResource> removed = new ArrayList<>();
    List<RemoveResource> remaining = new ArrayList<>();
    List<RemoveError> errors = new ArrayList<>(result.errors());
    TerminalStatus status = result.status();
    boolean retryable = result.retryable();
    if (status == TerminalStatus.SUCCEEDED) {
      var controllerRemoval = lifecycle.removeControllerRuntime(operation.swarmId());
      remaining.addAll(controllerRemoval.failedResources());
      errors.addAll(controllerRemoval.errors());
      if (!controllerRemoval.succeeded()) {
        status = TerminalStatus.FAILED;
        retryable = true;
      } else {
        List<RemoveResource> targets = new ArrayList<>(result.targetResources());
        targets.addAll(controllerRemoval.targetResources());
        var verification = removalVerifier.verifyAbsent(targets);
        removed.addAll(verification.removedResources());
        remaining.addAll(verification.remainingResources());
        errors.addAll(verification.errors());
        if (!verification.succeeded()) {
          status = TerminalStatus.FAILED;
          retryable = true;
        } else {
          CleanupResult cleanup = removeRuntimeDirectoryAndRegistry(operation.swarmId());
          removed.addAll(cleanup.removedResources());
          remaining.addAll(cleanup.remainingResources());
          errors.addAll(cleanup.errors());
          if (!cleanup.succeeded()) {
            status = TerminalStatus.FAILED;
            retryable = true;
          }
        }
      }
    } else {
      remaining.addAll(result.targetResources());
    }
    Map<String, Object> context = removeContext(result.controllerInstance(), removed, remaining, errors);
    TerminalResult terminal = new TerminalResult(status, retryable, context);
    if (status == TerminalStatus.SUCCEEDED) {
      RemoveResource terminalEvidence = new RemoveResource(
          io.pockethive.swarm.model.lifecycle.RemoveResourceType.TERMINAL_EVIDENCE,
          operation.correlationId());
      removed.add(terminalEvidence);
      context = removeContext(result.controllerInstance(), removed, remaining, errors);
      terminal = new TerminalResult(status, retryable, context);
      try {
        hiveJournal.appendDurably(result.runId(), HiveJournalEntry.info(
            operation.swarmId(), HiveJournal.Direction.LOCAL, OPERATION_TERMINAL_KIND,
            ControlPlaneOperations.signalForType(operation.type()), ROLE,
            new ControlScope(operation.swarmId(), ROLE, instanceId),
            operation.correlationId(), operation.idempotencyKey(), null,
            Map.of("status", status.wireValue(), "terminal", context), null, null));
      } catch (RuntimeException failure) {
        removed.remove(terminalEvidence);
        remaining.add(terminalEvidence);
        errors.add(removeError(failure, terminalEvidence));
        status = TerminalStatus.FAILED;
        retryable = true;
        context = removeContext(result.controllerInstance(), removed, remaining, errors);
        terminal = new TerminalResult(status, retryable, context);
      }
    }
    OperationCompletion completion = operations.recordResult(
        operation.swarmId(), OperationType.REMOVE, operation.target(),
        operation.correlationId(), operation.idempotencyKey(),
        terminalState(status), terminal, Instant.now());
    if (completion == OperationCompletion.COMPLETED) {
      SwarmOperation completed = operations.findByCorrelation(operation.correlationId()).orElseThrow();
      outcomes.publish(completed, runtime);
    }
  }

  private static Map<String, Object> removeContext(
      String controllerInstance,
      List<RemoveResource> removed,
      List<RemoveResource> remaining,
      List<RemoveError> errors) {
    Map<String, Object> context = new LinkedHashMap<>();
    context.put("target", new Target(CONTROLLER_ROLE, controllerInstance));
    context.put("removedResources", List.copyOf(removed));
    context.put("remainingResources", List.copyOf(remaining));
    context.put("errors", List.copyOf(errors));
    return context;
  }

  private CleanupResult removeRuntimeDirectoryAndRegistry(String swarmId) {
    RemoveResource runtimeDirectory = new RemoveResource(
        io.pockethive.swarm.model.lifecycle.RemoveResourceType.RUNTIME_DIRECTORY,
        swarmId);
    RemoveResource registryEntry = new RemoveResource(
        io.pockethive.swarm.model.lifecycle.RemoveResourceType.REGISTRY_ENTRY,
        swarmId);
    List<RemoveResource> removed = new ArrayList<>();
    List<RemoveResource> remaining = new ArrayList<>();
    List<RemoveError> errors = new ArrayList<>();
    try {
      removeStore.deleteSwarmRuntime(swarmId);
      if (removeStore.swarmRuntimeExists(swarmId)) {
        throw new IllegalStateException("Runtime directory still exists after deletion");
      }
      removed.add(runtimeDirectory);
    } catch (RuntimeException failure) {
      remaining.add(runtimeDirectory);
      errors.add(removeError(failure, runtimeDirectory));
      return new CleanupResult(removed, remaining, errors);
    }
    store.remove(swarmId);
    if (store.find(swarmId).isPresent()) {
      IllegalStateException failure = new IllegalStateException("Swarm registry entry still exists after removal");
      remaining.add(registryEntry);
      errors.add(removeError(failure, registryEntry));
    } else {
      removed.add(registryEntry);
    }
    return new CleanupResult(removed, remaining, errors);
  }

  private static RemoveError removeError(RuntimeException failure, RemoveResource resource) {
    return new RemoveError(
        failure.getClass().getSimpleName(),
        Objects.toString(failure.getMessage(), failure.getClass().getName()),
        resource);
  }

  private record CleanupResult(
      List<RemoveResource> removedResources,
      List<RemoveResource> remainingResources,
      List<RemoveError> errors) {
    private CleanupResult {
      removedResources = List.copyOf(removedResources);
      remainingResources = List.copyOf(remainingResources);
      errors = List.copyOf(errors);
    }

    private boolean succeeded() {
      return remainingResources.isEmpty() && errors.isEmpty();
    }
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

  private Map<String, Object> runtimeMeta(String swarmId) {
    Swarm swarm = store.find(swarmId).orElse(null);
    if (swarm == null) {
      return Map.of();
    }
    return Map.of(
        "templateId", requireText("templateId", swarm.templateId()),
        "runId", requireText("runId", swarm.getRunId()));
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

  @Scheduled(fixedRate = STATUS_INTERVAL_MS)
  public void status() {
    sendStatusDelta();
  }

  public void requestStatusFull() {
    ControlPlaneEmitter.StatusContext context = ControlPlaneEmitter.StatusContext.of(builder -> {
      builder.workPlaneEnabled(false)
          .enabledRequired(false)
          .filesystemEnabled(true)
          .tpsEnabled(false)
          .controlIn(controlQueue)
          .controlRoutes(controlRoutes.toArray(String[]::new))
          .data("swarmCount", store.count())
          .data("startedAt", startedAt);
      var adapterType = lifecycle.currentComputeAdapterType();
      if (adapterType != null) {
        builder.data("computeAdapter", adapterType.name());
      }
    });
    statusEmitter.emitStatusSnapshot(context);
  }

  private void sendStatusDelta() {
    statusEmitter.emitStatusDelta(ControlPlaneEmitter.StatusContext.of(builder ->
        builder.workPlaneEnabled(false)
            .enabledRequired(false)
            .tpsEnabled(false)
            .controlIn(controlQueue)
            .controlRoutes(controlRoutes.toArray(String[]::new))
            .data("swarmCount", store.count())));
  }

  private static void requireRoutingMatchesEnvelope(RoutingKey key, CommandResult result, String signal) {
    if (!signal.equals(result.type())
        || !key.swarmId().equals(result.scope().swarmId())
        || !key.role().equals(result.scope().role())
        || !key.instance().equals(result.scope().instance())) {
      throw new IllegalArgumentException("Result envelope identity does not match its routing key");
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

  private static RoutingKey requireEventKey(String routingKey) {
    RoutingKey key = ControlPlaneRouting.parseEvent(routingKey);
    if (key == null || key.type() == null) {
      throw new IllegalArgumentException("Invalid event routing key: " + routingKey);
    }
    return key;
  }

  private static OperationState terminalState(TerminalStatus status) {
    return switch (status) {
      case SUCCEEDED -> OperationState.SUCCEEDED;
      case REJECTED -> OperationState.REJECTED;
      case FAILED -> OperationState.FAILED;
      case TIMED_OUT -> OperationState.TIMED_OUT;
    };
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

  private List<String> resolveControlRoutes(ControlPlaneRouteCatalog catalog) {
    List<String> routes = new ArrayList<>();
    collectRoutes(routes, catalog.lifecycleEvents());
    collectRoutes(routes, catalog.otherEvents());
    return List.copyOf(routes);
  }

  private void collectRoutes(List<String> target, Set<String> templates) {
    for (String template : templates) {
      target.add(template.replace(ControlPlaneRouteCatalog.INSTANCE_TOKEN, instanceId));
    }
  }

  private void journalDrop(String swarmId, String routingKey, String reason, String body, Exception exception) {
    String resolved = swarmId == null || swarmId.isBlank() ? "hive" : swarmId;
    journalErrors.errorDrop(
        resolved, HiveJournal.Direction.IN, "event-dropped",
        new ControlScope(resolved, ROLE, instanceId), routingKey, reason, body, exception);
  }

  private static String bestEffortSwarmId(String routingKey) {
    RoutingKey key = ControlPlaneRouting.parseEvent(routingKey);
    return key == null || ControlScope.isAll(key.swarmId()) ? null : key.swarmId();
  }

  private static String requireText(String field, String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value.trim();
  }

  private static String snippet(String payload) {
    if (payload == null) {
      return "";
    }
    String stripped = payload.strip();
    return stripped.length() <= 300 ? stripped : stripped.substring(0, 300) + "…";
  }
}
