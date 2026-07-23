package io.pockethive.orchestrator.app;

import io.pockethive.control.CommandOutcome;
import io.pockethive.control.ControlScope;
import io.pockethive.control.ConfirmationScope;
import io.pockethive.controlplane.messaging.ControlPlanePublisher;
import io.pockethive.controlplane.ControlPlaneOperations;
import io.pockethive.controlplane.messaging.EventMessage;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import io.pockethive.swarm.model.lifecycle.SwarmOperation;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Publishes the single public terminal event owned by the Orchestrator operation coordinator. */
public final class OperationOutcomePublisher {

  private final ControlPlanePublisher publisher;
  private final String instanceId;
  private final Set<String> publishedCorrelations = new HashSet<>();

  public OperationOutcomePublisher(
      ControlPlanePublisher publisher,
      String instanceId) {
    this.publisher = Objects.requireNonNull(publisher, "publisher");
    this.instanceId = requireText("instanceId", instanceId);
  }

  public synchronized boolean publish(SwarmOperation operation, Map<String, Object> runtime) {
    Objects.requireNonNull(operation, "operation");
    if (!operation.terminal()) {
      throw new IllegalArgumentException("Only terminal operations can publish outcomes");
    }
    if (!publishedCorrelations.add(operation.correlationId())) {
      return false;
    }
    try {
      ControlScope scope = new ControlScope(operation.swarmId(), CommandOutcome.OWNER_ROLE, instanceId);
      CommandOutcome outcome = new CommandOutcome(
          operation.completedAt(),
          io.pockethive.control.ControlPlaneEnvelopeVersion.CURRENT,
          CommandOutcome.KIND,
          ControlPlaneOperations.signalForType(operation.type()),
          instanceId,
          scope,
          operation.correlationId(),
          operation.idempotencyKey(),
          runtime,
          operation.terminalResult());
      String routingKey = ControlPlaneRouting.event(
          CommandOutcome.KIND,
          outcome.type(),
          new ConfirmationScope(scope.swarmId(), scope.role(), scope.instance()));
      publisher.publishEvent(new EventMessage(routingKey, outcome));
      return true;
    } catch (RuntimeException exception) {
      publishedCorrelations.remove(operation.correlationId());
      throw exception;
    }
  }

  public synchronized boolean isPublished(String correlationId) {
    return publishedCorrelations.contains(requireText("correlationId", correlationId));
  }

  private static String requireText(String field, String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value.trim();
  }
}
