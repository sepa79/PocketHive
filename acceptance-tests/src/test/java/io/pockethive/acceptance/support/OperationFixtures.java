package io.pockethive.acceptance.support;

import io.pockethive.swarm.model.BeeRoles;
import io.pockethive.swarm.model.NetworkMode;
import io.pockethive.swarm.model.lifecycle.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class OperationFixtures {
  public static final String SWARM = "acceptance-test";
  public static final String RUN = "test-run";
  private OperationFixtures() {}
  public static ControlResponse receipt() {
    String correlation = UUID.randomUUID().toString();
    return new ControlResponse(correlation, UUID.randomUUID().toString(),
        "/api/swarms/" + SWARM + "/operations/" + correlation, "opaque-unused-topic", 5000);
  }
  public static SwarmCreateRequest createRequest(ControlResponse receipt) {
    return SwarmCreateRequest.of("test-fixture", receipt.idempotencyKey(), false, null, null, NetworkMode.DIRECT, null);
  }
  public static SwarmOperation operation(ControlResponse receipt, OperationType type, OperationState state,
      Map<String, Object> context) {
    Instant now = Instant.now();
    var accepted = SwarmOperation.accepted(SWARM, type, new Target(BeeRoles.SWARM_CONTROLLER, "controller"),
        new RuntimeMetadata("test-fixture", RUN), receipt.correlationId(), receipt.idempotencyKey(),
        now.minusSeconds(1), now.plusSeconds(5));
    if (state == OperationState.ACCEPTED) return accepted;
    if (state == OperationState.DISPATCHED) return accepted.dispatch(now);
    return accepted.complete(state, new TerminalResult(TerminalStatus.valueOf(state.name()), false, context), now);
  }
  public static SwarmOperation succeeded(ControlResponse receipt, OperationType type) {
    Map<String, Object> context = type == OperationType.REMOVE
        ? Map.of("removedResources", List.of(Map.of("type", "REGISTRY_ENTRY", "id", SWARM, "plane", "NONE")),
            "remainingResources", List.of(), "errors", List.of())
        : Map.of();
    return operation(receipt, type, OperationState.SUCCEEDED, context);
  }
}
