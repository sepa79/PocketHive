package io.pockethive.manager.scenario;

import java.util.Objects;

/**
 * A reusable, higher-level behaviour that can react to manager runtime state.
 * <p>
 * Scenarios are deliberately tick-based: callers drive them by invoking
 * {@link ScenarioEngine#tick()} on their own scheduler.
 */
public interface Scenario {

  /**
   * Logical identifier for this scenario (for logging and diagnostics only).
   */
  String id();

  /**
   * Execute one logical "tick" of this scenario.
   *
   * @param view    read-only view of the manager runtime
   * @param context helper exposing control-plane/config primitives
   */
  void onTick(ManagerRuntimeView view, ScenarioContext context);

  /**
   * Small helper to enforce non-null semantics for scenarios.
   */
  static Scenario of(Scenario delegate) {
    return Objects.requireNonNull(delegate, "scenario");
  }
}

