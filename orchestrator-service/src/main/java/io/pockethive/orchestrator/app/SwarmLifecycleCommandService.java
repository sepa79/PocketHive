package io.pockethive.orchestrator.app;

import io.pockethive.control.ControlScope;
import io.pockethive.control.ControlSignal;
import io.pockethive.controlplane.ControlPlaneOperations;
import io.pockethive.controlplane.ControlPlaneRoles;
import io.pockethive.controlplane.ControlPlaneSignals;
import io.pockethive.controlplane.messaging.ControlPlanePublisher;
import io.pockethive.controlplane.messaging.ControlSignals;
import io.pockethive.controlplane.messaging.SignalMessage;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import io.pockethive.controlplane.spring.ControlPlaneProperties;
import io.pockethive.observability.ControlPlaneJson;
import io.pockethive.orchestrator.domain.HiveJournal;
import io.pockethive.orchestrator.domain.HiveJournal.HiveJournalEntry;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmOperationCoordinator;
import io.pockethive.orchestrator.domain.SwarmStore;
import io.pockethive.controlplane.filesystem.FilesystemSwarmRemoveStore;
import io.pockethive.swarm.model.lifecycle.RemoveRequest;
import io.pockethive.swarm.model.lifecycle.OperationType;
import io.pockethive.swarm.model.lifecycle.RuntimeIntent;
import io.pockethive.swarm.model.lifecycle.Target;
import io.pockethive.swarm.model.lifecycle.WorkloadIntent;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Canonical START/STOP/REMOVE command path shared by REST and governed cleanup. */
@Component
public final class SwarmLifecycleCommandService {

  private final SwarmStore swarms;
  private final OperationDispatchService operations;
  private final ControlPlanePublisher publisher;
  private final FilesystemSwarmRemoveStore removeStore;
  private final HiveJournal journal;
  private final String originInstanceId;

  public SwarmLifecycleCommandService(
      SwarmStore swarms,
      OperationDispatchService operations,
      ControlPlanePublisher publisher,
      FilesystemSwarmRemoveStore removeStore,
      HiveJournal journal,
      ControlPlaneProperties properties) {
    this.swarms = Objects.requireNonNull(swarms, "swarms");
    this.operations = Objects.requireNonNull(operations, "operations");
    this.publisher = Objects.requireNonNull(publisher, "publisher");
    this.removeStore = Objects.requireNonNull(removeStore, "removeStore");
    this.journal = Objects.requireNonNull(journal, "journal");
    this.originInstanceId = requireText("control-plane instanceId", properties.getInstanceId());
  }

  public SwarmOperationCoordinator.Reservation dispatch(
      OperationType operationType,
      String swarmId,
      String idempotencyKey,
      Duration timeout) {
    if (operationType != OperationType.START
        && operationType != OperationType.STOP
        && operationType != OperationType.REMOVE) {
      throw new IllegalArgumentException("Unsupported controller lifecycle operation: " + operationType);
    }
    Swarm swarm = swarms.find(swarmId)
        .orElseThrow(() -> new IllegalStateException("Swarm " + swarmId + " is not registered"));
    String controllerInstance = requireText("controller instance", swarm.getInstanceId());
    Target operationTarget = new Target(ControlPlaneRoles.SWARM_CONTROLLER, controllerInstance);
    return operations.dispatch(
        swarmId,
        operationType,
        operationTarget,
        idempotencyKey,
        timeout,
        correlationId -> execute(
            operationType, swarm, operationTarget, correlationId, idempotencyKey, timeout));
  }

  private void execute(
      OperationType operationType,
      Swarm swarm,
      Target operationTarget,
      String correlationId,
      String idempotencyKey,
      Duration timeout) {
    switch (operationType) {
      case START -> swarm.requestWorkload(WorkloadIntent.RUNNING);
      case STOP -> swarm.requestWorkload(WorkloadIntent.STOPPED);
      case REMOVE -> {
        swarm.requestRuntime(RuntimeIntent.ABSENT);
        removeStore.saveRequest(RemoveRequest.create(
            swarm.getId(),
            swarm.getRunId(),
            operationTarget.instance(),
            correlationId,
            idempotencyKey,
            Instant.now()));
      }
      default -> throw new IllegalArgumentException("Unsupported lifecycle operation: " + operationType);
    }
    String signal = ControlPlaneOperations.signalForType(operationType);
    ControlScope scope = ControlScope.forInstance(
        swarm.getId(), operationTarget.role(), operationTarget.instance());
    ControlSignal payload = switch (operationType) {
      case START ->
          ControlSignals.swarmStart(originInstanceId, scope, correlationId, idempotencyKey);
      case STOP ->
          ControlSignals.swarmStop(originInstanceId, scope, correlationId, idempotencyKey);
      case REMOVE ->
          ControlSignals.swarmRemove(originInstanceId, scope, correlationId, idempotencyKey);
      default -> throw new IllegalArgumentException("Unsupported lifecycle operation: " + operationType);
    };
    String routingKey = ControlPlaneRouting.signal(
        signal, swarm.getId(), operationTarget.role(), operationTarget.instance());
    publisher.publishSignal(new SignalMessage(
        routingKey,
        ControlPlaneJson.write(payload, "control signal " + signal)));
    appendJournal(swarm.getId(), signal, scope, correlationId, idempotencyKey, routingKey, timeout);
  }

  private void appendJournal(
      String swarmId,
      String signal,
      ControlScope target,
      String correlationId,
      String idempotencyKey,
      String routingKey,
      Duration timeout) {
    try {
      var data = new LinkedHashMap<String, Object>();
      data.put("controllerInstance", target.instance());
      data.put("timeoutMs", timeout.toMillis());
      journal.append(HiveJournalEntry.info(
          swarmId,
          HiveJournal.Direction.OUT,
          ControlSignal.KIND,
          signal,
          ControlPlaneRoles.ORCHESTRATOR,
          target,
          correlationId,
          idempotencyKey,
          routingKey,
          data,
          null,
          null));
    } catch (Exception ignored) {
      // Journal is observability evidence and does not own operation dispatch.
    }
  }

  private static String requireText(String field, String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value.trim();
  }
}
