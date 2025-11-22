package io.pockethive.swarmcontroller.scenario;

/**
 * Placeholder contract for future swarm scenarios.
 * <p>
 * A scenario represents a higher-level behaviour (e.g. time- or event-driven
 * config changes) that can be evaluated by the Swarm Controller.
 */
public interface SwarmScenario {

  /**
   * Logical identifier for this scenario (for logging/diagnostics only).
   */
  String id();
}

