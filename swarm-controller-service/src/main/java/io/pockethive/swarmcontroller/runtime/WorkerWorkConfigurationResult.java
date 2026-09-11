package io.pockethive.swarmcontroller.runtime;

import java.util.Map;

/**
 * Responsibility: carry frozen environment and its corresponding bootstrap projection for spec assembly.
 * Must not: validate settings, own domain state or expose configuration in diagnostics.
 * Contract: RESP-CONTROLLER-WORK-CONFIGURATION — docs/architecture/runtime-responsibilities.md#resp-controller-work-configuration.
 * Outer maps are immutable; unchanged nested domain values are borrowed read-only projections.
 */
public record WorkerWorkConfigurationResult(Map<String, String> environment, Map<String, Object> bootstrapConfig) {
  public WorkerWorkConfigurationResult {
    environment = Map.copyOf(environment);
    bootstrapConfig = Map.copyOf(bootstrapConfig);
  }

  @Override
  public String toString() {
    return "WorkerWorkConfigurationResult[redacted]";
  }
}
