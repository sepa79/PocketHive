package io.pockethive.swarm.model.lifecycle;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record RemoveResult(
    String schema,
    String swarmId,
    String runId,
    String controllerInstance,
    String correlationId,
    String idempotencyKey,
    TerminalStatus status,
    boolean retryable,
    List<RemoveResource> targetResources,
    List<RemoveError> errors,
    Instant completedAt
) {

  public static final String SCHEMA = "pockethive/swarm-remove-result/v2";

  public RemoveResult {
    if (!SCHEMA.equals(schema)) {
      throw new IllegalArgumentException("Unsupported remove result schema: " + schema);
    }
    swarmId = ContractValues.requireText("swarmId", swarmId);
    runId = ContractValues.requireText("runId", runId);
    controllerInstance = ContractValues.requireText("controllerInstance", controllerInstance);
    correlationId = ContractValues.requireText("correlationId", correlationId);
    idempotencyKey = ContractValues.requireText("idempotencyKey", idempotencyKey);
    status = Objects.requireNonNull(status, "status");
    if (status != TerminalStatus.SUCCEEDED && status != TerminalStatus.FAILED) {
      throw new IllegalArgumentException("Remove result status must be SUCCEEDED or FAILED");
    }
    targetResources = ContractValues.immutableList("targetResources", targetResources);
    errors = ContractValues.immutableList("errors", errors);
    completedAt = Objects.requireNonNull(completedAt, "completedAt");
    if (status == TerminalStatus.SUCCEEDED && !errors.isEmpty()) {
      throw new IllegalArgumentException("Successful remove cannot contain errors");
    }
    if (status == TerminalStatus.FAILED && errors.isEmpty()) {
      throw new IllegalArgumentException("Failed remove requires errors");
    }
  }

  public static RemoveResult succeeded(
      String swarmId,
      String runId,
      String controllerInstance,
      String correlationId,
      String idempotencyKey,
      List<RemoveResource> targetResources,
      Instant completedAt) {
    return new RemoveResult(
        SCHEMA,
        swarmId,
        runId,
        controllerInstance,
        correlationId,
        idempotencyKey,
        TerminalStatus.SUCCEEDED,
        false,
        targetResources,
        List.of(),
        completedAt);
  }
}
