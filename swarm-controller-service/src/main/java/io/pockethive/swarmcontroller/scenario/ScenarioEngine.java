package io.pockethive.swarmcontroller.scenario;

import io.pockethive.swarmcontroller.guard.SwarmGuard;
import java.util.List;
import java.util.Objects;

/**
 * Minimal placeholder for a future scenario engine.
 * <p>
 * Implements {@link SwarmGuard} so it can be scheduled alongside other guards,
 * but currently performs no work.
 */
public final class ScenarioEngine implements SwarmGuard {

  private final List<SwarmScenario> scenarios;

  public ScenarioEngine(List<SwarmScenario> scenarios) {
    this.scenarios = List.copyOf(Objects.requireNonNull(scenarios, "scenarios"));
  }

  public boolean isEmpty() {
    return scenarios.isEmpty();
  }

  @Override
  public void start() {
    // no-op placeholder
  }

  @Override
  public void stop() {
    // no-op placeholder
  }

  @Override
  public void pause() {
    // no-op placeholder
  }

  @Override
  public void resume() {
    // no-op placeholder
  }
}

