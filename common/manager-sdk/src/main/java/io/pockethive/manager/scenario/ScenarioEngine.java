package io.pockethive.manager.scenario;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Minimal, transport-agnostic scenario engine.
 * <p>
 * Callers supply a {@link Supplier} of {@link ManagerRuntimeView} and a
 * {@link ScenarioContext}; the engine simply invokes all registered scenarios
 * on each {@link #tick()}.
 */
public final class ScenarioEngine {

  private final List<Scenario> scenarios;
  private final Supplier<ManagerRuntimeView> viewSupplier;
  private final ScenarioContext context;

  public ScenarioEngine(List<Scenario> scenarios,
                        Supplier<ManagerRuntimeView> viewSupplier,
                        ScenarioContext context) {
    this.scenarios = List.copyOf(Objects.requireNonNull(scenarios, "scenarios"));
    this.viewSupplier = Objects.requireNonNull(viewSupplier, "viewSupplier");
    this.context = Objects.requireNonNull(context, "context");
  }

  public boolean isEmpty() {
    return scenarios.isEmpty();
  }

  /**
   * Execute one logical tick across all scenarios.
   */
  public void tick() {
    if (scenarios.isEmpty()) {
      return;
    }
    ManagerRuntimeView view = viewSupplier.get();
    if (view == null) {
      return;
    }
    for (Scenario scenario : scenarios) {
      scenario.onTick(view, context);
    }
  }
}

