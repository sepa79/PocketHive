package io.pockethive.manager.scenario;

import io.pockethive.manager.runtime.ConfigFanout;
import io.pockethive.manager.runtime.ManagerLifecycle;
import java.util.Objects;

/**
 * Helper object passed to scenarios so they can interact with the manager
 * without depending directly on transport-specific types.
 */
public final class ScenarioContext {

  private final String swarmId;
  private final ManagerLifecycle manager;
  private final ConfigFanout configFanout;

  public ScenarioContext(String swarmId, ManagerLifecycle manager, ConfigFanout configFanout) {
    if (swarmId == null || swarmId.isBlank()) {
      throw new IllegalArgumentException("swarmId must not be null or blank");
    }
    this.swarmId = swarmId.trim();
    this.manager = Objects.requireNonNull(manager, "manager");
    this.configFanout = Objects.requireNonNull(configFanout, "configFanout");
  }

  public String swarmId() {
    return swarmId;
  }

  /**
   * Access to the manager lifecycle for failure signalling or status checks.
   */
  public ManagerLifecycle manager() {
    return manager;
  }

  /**
   * Access to shared config fan-out utilities (e.g. emitting config-update
   * signals).
   */
  public ConfigFanout configFanout() {
    return configFanout;
  }
}
