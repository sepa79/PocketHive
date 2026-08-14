package io.pockethive.manager.scenario;

/**
 * Command-only lifecycle boundary available to scenario evaluators.
 *
 * <p>The implementation must delegate to the owning Controller lifecycle core. It must not keep
 * workload state, readiness observations, or transition rules of its own.
 */
public interface ScenarioLifecyclePort {

  void enableAll();

  void setWorkEnabled(boolean enabled);
}
