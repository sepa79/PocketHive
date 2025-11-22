package io.pockethive.swarmcontroller.scenario;

import io.pockethive.manager.scenario.ManagerRuntimeView;
import io.pockethive.manager.scenario.Scenario;
import io.pockethive.manager.scenario.ScenarioContext;
import java.util.Objects;

/**
 * Placeholder scenario used to validate wiring between the Swarm Controller
 * and the Manager SDK scenario engine. It intentionally performs no work.
 */
public final class NoopScenario implements Scenario {

  private final String id;

  public NoopScenario(String id) {
    this.id = Objects.requireNonNull(id, "id");
  }

  @Override
  public String id() {
    return id;
  }

  @Override
  public void onTick(ManagerRuntimeView view, ScenarioContext context) {
    // no-op by design
  }
}

