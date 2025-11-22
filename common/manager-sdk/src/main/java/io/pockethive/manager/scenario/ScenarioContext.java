package io.pockethive.manager.scenario;

import io.pockethive.manager.runtime.ConfigFanout;
import io.pockethive.manager.runtime.ManagerLifecycle;
import java.util.Objects;

/**
 * Helper object passed to scenarios so they can interact with the manager
 * without depending directly on transport-specific types.
 */
public final class ScenarioContext {

  private final ManagerLifecycle manager;
  private final ConfigFanout configFanout;

  public ScenarioContext(ManagerLifecycle manager, ConfigFanout configFanout) {
    this.manager = Objects.requireNonNull(manager, "manager");
    this.configFanout = Objects.requireNonNull(configFanout, "configFanout");
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

