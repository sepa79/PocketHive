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
    List<RemoveResource> removedResources,
    List<RemoveResource> remainingResources,
    List<RemoveError> errors,
    Instant completedAt
) {

  public static final String SCHEMA = "pockethive/swarm-remove-result/v1";

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
    removedResources = ContractValues.immutableList("removedResources", removedResources);
    remainingResources = ContractValues.immutableList("remainingResources", remainingResources);
    errors = ContractValues.immutableList("errors", errors);
    completedAt = Objects.requireNonNull(completedAt, "completedAt");
    if (status == TerminalStatus.SUCCEEDED && !remainingResources.isEmpty()) {
      throw new IllegalArgumentException("Successful remove cannot contain remainingResources");
    }
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
      List<RemoveResource> removedResources,
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
        removedResources,
        List.of(),
        List.of(),
        completedAt);
  }
}
