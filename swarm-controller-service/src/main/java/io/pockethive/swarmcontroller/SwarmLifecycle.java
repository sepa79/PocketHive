package io.pockethive.swarmcontroller;

public interface SwarmLifecycle {
  void prepare(String templateJson);
  void start(String planJson);
  void stop();
  void remove();
  SwarmStatus getStatus();
  /**
   * Record readiness of a component identified by role and instance.
   * @return true when all expected components are ready
   */
  boolean markReady(String role, String instance);

  /**
   * Update last-seen timestamp for a component.
   * @param role component role
   * @param instance component instance
   */
  void updateHeartbeat(String role, String instance);

  /**
   * Apply the first scenario step configuration while keeping components disabled.
   */
  void applyScenarioStep(String stepJson);

  /**
   * Enable all components and begin scenario execution.
   */
  void enableAll();
}
