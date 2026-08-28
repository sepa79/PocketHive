package io.pockethive.orchestrator.app;

import io.pockethive.control.ControlScope;
import io.pockethive.control.JournalEvent;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.ControlPlaneOperations;
import io.pockethive.controlplane.ControlPlaneRoles;
import io.pockethive.controlplane.ControlPlaneSignals;
import io.pockethive.controlplane.filesystem.FilesystemSwarmRemoveStore;
import io.pockethive.orchestrator.domain.HiveJournal;
import io.pockethive.orchestrator.domain.HiveJournal.HiveJournalEntry;
import io.pockethive.orchestrator.domain.OperationCompletion;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmOperationCoordinator;
import io.pockethive.orchestrator.domain.SwarmStore;
import io.pockethive.orchestrator.runtime.RuntimeRemovalPostconditionVerifier;
import io.pockethive.swarm.model.lifecycle.OperationState;
import io.pockethive.swarm.model.lifecycle.OperationType;
import io.pockethive.swarm.model.lifecycle.RemoveError;
import io.pockethive.swarm.model.lifecycle.RemoveResource;
import io.pockethive.swarm.model.lifecycle.RemoveResourceType;
import io.pockethive.swarm.model.lifecycle.RemoveResult;
import io.pockethive.swarm.model.lifecycle.SwarmOperation;
import io.pockethive.swarm.model.lifecycle.Target;
import io.pockethive.swarm.model.lifecycle.TerminalResult;
import io.pockethive.swarm.model.lifecycle.TerminalStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Responsibility: Converge filesystem remove evidence through verified runtime, topology, and storage cleanup.
 * Must not: Consume transport messages, expire unrelated operations, or infer success before all remove postconditions.
 * Contract: A REMOVE outcome is published only after the coordinator accepts verified terminal evidence.
 */
@Component
public class SwarmRemovalConvergenceHandler {

  private static final String ROLE = ControlPlaneRoles.ORCHESTRATOR;
  private static final String CONTROLLER_ROLE = ControlPlaneRoles.SWARM_CONTROLLER;
  private static final String OPERATION_TERMINAL_KIND = "operation-terminal";

  private final SwarmStore store;
  private final ContainerLifecycleManager lifecycle;
  private final HiveJournal hiveJournal;
  private final SwarmOperationCoordinator operations;
  private final OperationOutcomePublisher outcomes;
  private final FilesystemSwarmRemoveStore removeStore;
  private final RuntimeRemovalPostconditionVerifier removalVerifier;
  private final SwarmNetworkBindingService networkBindings;
  private final String instanceId;

  public SwarmRemovalConvergenceHandler(
      SwarmStore store,
      ContainerLifecycleManager lifecycle,
      HiveJournal hiveJournal,
      SwarmOperationCoordinator operations,
      OperationOutcomePublisher outcomes,
      FilesystemSwarmRemoveStore removeStore,
      RuntimeRemovalPostconditionVerifier removalVerifier,
      SwarmNetworkBindingService networkBindings,
      @Qualifier("managerControlPlaneIdentity") ControlPlaneIdentity identity) {
    this.store = Objects.requireNonNull(store, "store");
    this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    this.hiveJournal = Objects.requireNonNull(hiveJournal, "hiveJournal");
    this.operations = Objects.requireNonNull(operations, "operations");
    this.outcomes = Objects.requireNonNull(outcomes, "outcomes");
    this.removeStore = Objects.requireNonNull(removeStore, "removeStore");
    this.removalVerifier = Objects.requireNonNull(removalVerifier, "removalVerifier");
    this.networkBindings = Objects.requireNonNull(networkBindings, "networkBindings");
    this.instanceId = Objects.requireNonNull(identity, "identity").instanceId();
  }

  void checkResults() {
    operations.operations().stream()
        .filter(operation -> operation.type() == OperationType.REMOVE && !operation.terminal())
        .forEach(operation -> {
          try {
            removeStore.findResult(operation.swarmId(), operation.correlationId())
                .ifPresent(result -> acceptResult(operation, result));
          } catch (RuntimeException failure) {
            failInvalidEvidence(operation, failure);
          }
        });
  }

  private void failInvalidEvidence(SwarmOperation operation, RuntimeException failure) {
    Swarm swarm = store.find(operation.swarmId()).orElse(null);
    RemoveResource controller = new RemoveResource(
        RemoveResourceType.CONTROLLER_RUNTIME,
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
      outcomes.publish(operations.findByCorrelation(operation.correlationId()).orElseThrow());
    }
  }

  private void acceptResult(SwarmOperation operation, RemoveResult result) {
    Swarm swarm = store.find(operation.swarmId()).orElseThrow();
    if (!result.swarmId().equals(operation.swarmId())
        || !result.runId().equals(swarm.getRunId())
        || !result.controllerInstance().equals(swarm.getInstanceId())
        || !result.correlationId().equals(operation.correlationId())
        || !result.idempotencyKey().equals(operation.idempotencyKey())) {
      throw new IllegalArgumentException("Remove result does not match the active operation identity");
    }
    List<RemoveResource> removed = new ArrayList<>();
    List<RemoveResource> remaining = new ArrayList<>();
    List<RemoveError> errors = new ArrayList<>(result.errors());
    TerminalStatus status = result.status();
    boolean retryable = result.retryable();
    if (status == TerminalStatus.SUCCEEDED) {
      var workerAndRabbitVerification = removalVerifier.verifyAbsent(result.targetResources());
      removed.addAll(workerAndRabbitVerification.removedResources());
      remaining.addAll(workerAndRabbitVerification.remainingResources());
      errors.addAll(workerAndRabbitVerification.errors());
      if (!workerAndRabbitVerification.succeeded()) {
        status = TerminalStatus.FAILED;
        retryable = true;
      } else {
        RemoveResource networkBinding = new RemoveResource(
            RemoveResourceType.NETWORK_BINDING,
            operation.swarmId());
        try {
          networkBindings.clearBindingAndVerifyAbsent(
              operation.swarmId(),
              operation.correlationId(),
              operation.idempotencyKey(),
              ROLE,
              ControlPlaneSignals.SWARM_REMOVE,
              ROLE);
          removed.add(networkBinding);
        } catch (RuntimeException failure) {
          remaining.add(networkBinding);
          errors.add(removeError(failure, networkBinding));
          status = TerminalStatus.FAILED;
          retryable = true;
        }
        if (status == TerminalStatus.SUCCEEDED) {
          var controllerRemoval = lifecycle.removeControllerRuntime(operation.swarmId());
          remaining.addAll(controllerRemoval.failedResources());
          errors.addAll(controllerRemoval.errors());
          if (!controllerRemoval.succeeded()) {
            status = TerminalStatus.FAILED;
            retryable = true;
          } else {
            var controllerVerification = removalVerifier.verifyAbsent(controllerRemoval.targetResources());
            removed.addAll(controllerVerification.removedResources());
            remaining.addAll(controllerVerification.remainingResources());
            errors.addAll(controllerVerification.errors());
            if (!controllerVerification.succeeded()) {
              status = TerminalStatus.FAILED;
              retryable = true;
            }
          }
        }
        if (status == TerminalStatus.SUCCEEDED) {
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
          RemoveResourceType.TERMINAL_EVIDENCE,
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
        status == TerminalStatus.SUCCEEDED ? OperationState.SUCCEEDED : OperationState.FAILED,
        terminal, Instant.now());
    if (completion == OperationCompletion.COMPLETED) {
      SwarmOperation completed = operations.findByCorrelation(operation.correlationId()).orElseThrow();
      outcomes.publish(completed);
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
    RemoveResource runtimeDirectory = new RemoveResource(RemoveResourceType.RUNTIME_DIRECTORY, swarmId);
    RemoveResource registryEntry = new RemoveResource(RemoveResourceType.REGISTRY_ENTRY, swarmId);
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
}
