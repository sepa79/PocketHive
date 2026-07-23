package io.pockethive.orchestrator.app;

import io.pockethive.control.ConfirmationScope;
import io.pockethive.control.CommandOutcome;
import io.pockethive.controlplane.ControlPlaneOperations;
import io.pockethive.controlplane.ControlPlaneRoles;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import io.pockethive.controlplane.spring.ControlPlaneProperties;
import io.pockethive.swarm.model.lifecycle.ControlResponse;
import io.pockethive.swarm.model.lifecycle.SwarmOperation;
import java.time.Duration;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Sole Orchestrator mapper from an accepted operation to its public REST acknowledgement. */
@Component
public final class ControlResponseFactory {

  private final String originInstanceId;

  public ControlResponseFactory(ControlPlaneProperties properties) {
    this.originInstanceId = requireText(
        "control-plane instanceId",
        Objects.requireNonNull(properties, "properties").getInstanceId());
  }

  public ControlResponse create(SwarmOperation operation, Duration timeout) {
    Objects.requireNonNull(operation, "operation");
    long timeoutMs = Objects.requireNonNull(timeout, "timeout").toMillis();
    String signal = ControlPlaneOperations.signalForType(operation.type());
    ConfirmationScope scope = new ConfirmationScope(
        operation.swarmId(), ControlPlaneRoles.ORCHESTRATOR, originInstanceId);
    return new ControlResponse(
        operation.correlationId(),
        operation.idempotencyKey(),
        "/api/swarms/" + operation.swarmId() + "/operations/" + operation.correlationId(),
        ControlPlaneRouting.event(CommandOutcome.KIND, signal, scope),
        timeoutMs);
  }

  private static String requireText(String field, String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value.trim();
  }
}
