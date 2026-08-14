package io.pockethive.swarm.model.lifecycle;

import java.time.Instant;
import java.util.Objects;

public record RemoveRequest(
    String schema,
    String swarmId,
    String runId,
    String controllerInstance,
    String correlationId,
    String idempotencyKey,
    Instant requestedAt
) {

  public static final String SCHEMA = "pockethive/swarm-remove-request/v1";

  public RemoveRequest {
    if (!SCHEMA.equals(schema)) {
      throw new IllegalArgumentException("Unsupported remove request schema: " + schema);
    }
    swarmId = ContractValues.requireText("swarmId", swarmId);
    runId = ContractValues.requireText("runId", runId);
    controllerInstance = ContractValues.requireText("controllerInstance", controllerInstance);
    correlationId = ContractValues.requireText("correlationId", correlationId);
    idempotencyKey = ContractValues.requireText("idempotencyKey", idempotencyKey);
    requestedAt = Objects.requireNonNull(requestedAt, "requestedAt");
  }

  public static RemoveRequest create(
      String swarmId,
      String runId,
      String controllerInstance,
      String correlationId,
      String idempotencyKey,
      Instant requestedAt) {
    return new RemoveRequest(
        SCHEMA, swarmId, runId, controllerInstance, correlationId, idempotencyKey, requestedAt);
  }
}
