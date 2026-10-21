package io.pockethive.swarm.model.lifecycle;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Responsibility: represent the read-only swarm state projection with a required observation object.
 * Must not: own lifecycle transitions or replace missing observation input with empty context.
 * Contract: Lifecycle wire shapes and enum values — docs/ARCHITECTURE.md#511-authority-boundaries;
 * docs/spec/swarm-lifecycle.schema.json#/$defs/SwarmStateView.
 */
public record SwarmStateView(
    String id,
    String runId,
    RuntimeIntent runtimeIntent,
    WorkloadIntent workloadIntent,
    ControllerState controllerState,
    WorkloadState workloadState,
    Health health,
    RuntimeResourceState runtimeResourceState,
    Instant observedAt,
    boolean observationStale,
    SwarmOperation activeOperation,
    String templateId,
    String controllerImage,
    List<WorkerSummary> bees,
    Map<String, Object> observation
) {

  public SwarmStateView {
    id = ContractValues.requireText("id", id);
    runId = ContractValues.requireText("runId", runId);
    runtimeIntent = Objects.requireNonNull(runtimeIntent, "runtimeIntent");
    workloadIntent = Objects.requireNonNull(workloadIntent, "workloadIntent");
    controllerState = Objects.requireNonNull(controllerState, "controllerState");
    workloadState = Objects.requireNonNull(workloadState, "workloadState");
    health = Objects.requireNonNull(health, "health");
    runtimeResourceState = Objects.requireNonNull(runtimeResourceState, "runtimeResourceState");
    if (runtimeIntent == RuntimeIntent.ABSENT && workloadIntent != WorkloadIntent.STOPPED) {
      throw new IllegalArgumentException("Runtime intent ABSENT requires workload intent STOPPED");
    }
    if (activeOperation != null && activeOperation.terminal()) {
      throw new IllegalArgumentException("activeOperation must be non-terminal");
    }
    templateId = ContractValues.optionalText(templateId);
    controllerImage = ContractValues.optionalText(controllerImage);
    bees = ContractValues.immutableList("bees", bees);
    observation = ContractValues.immutableContext(Objects.requireNonNull(observation, "observation"));
  }
}
