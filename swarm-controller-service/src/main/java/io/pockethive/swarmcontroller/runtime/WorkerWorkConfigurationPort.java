package io.pockethive.swarmcontroller.runtime;

import io.pockethive.swarm.model.Bee;
import java.util.Map;

/**
 * Responsibility: supply composed Work environment and bootstrap for one worker plan.
 * Must not: expose infrastructure clients, mutate supplied maps or perform provisioning.
 * Contract: RESP-CONTROLLER-WORK-CONFIGURATION — docs/architecture/runtime-responsibilities.md#resp-controller-work-configuration.
 */
public interface WorkerWorkConfigurationPort {
  /** Reject unsupported declaration controls before the caller resolves external planning context. */
  void validateDeclaration(Bee bee);

  WorkerWorkConfigurationResult compose(Bee bee, Map<String, Object> effectiveConfig,
      Map<String, String> baseEnvironment);
}
