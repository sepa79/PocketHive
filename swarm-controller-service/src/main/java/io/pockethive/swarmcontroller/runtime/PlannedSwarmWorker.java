package io.pockethive.swarmcontroller.runtime;

import io.pockethive.manager.runtime.WorkerSpec;
import java.util.Map;
import java.util.Objects;

/**
 * Responsibility: Carry one fully resolved worker specification with its canonical bootstrap configuration.
 * Must not: Resolve configuration, provision infrastructure, or mutate runtime state.
 * Contract: Both values are immutable snapshots produced from the same scenario bee.
 */
public record PlannedSwarmWorker(WorkerSpec spec, Map<String, Object> bootstrapConfig) {

  public PlannedSwarmWorker {
    WorkerSpec resolvedSpec = Objects.requireNonNull(spec, "spec");
    spec = new WorkerSpec(
        resolvedSpec.id(),
        resolvedSpec.role(),
        resolvedSpec.image(),
        Map.copyOf(resolvedSpec.environment()),
        java.util.List.copyOf(resolvedSpec.volumes()));
    bootstrapConfig = bootstrapConfig == null || bootstrapConfig.isEmpty()
        ? Map.of()
        : Map.copyOf(bootstrapConfig);
  }
}
