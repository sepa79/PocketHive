package io.pockethive.swarmcontroller;

public interface SwarmLifecycle {
  void prepare(String templateJson, String correlationId, String idempotencyKey);
  void start(String planJson, String correlationId, String idempotencyKey);
  void stop(String correlationId, String idempotencyKey);
  void remove(String correlationId, String idempotencyKey);
  SwarmStatus getStatus();
  /**
   * Record readiness of a component identified by role and instance.
   * @return true when all expected components are ready
   */
  boolean markReady(String role, String instance);

  /**
   * Apply the first scenario step configuration while keeping components disabled.
   */
  void applyScenarioStep(String stepJson);

  /**
   * Enable all components and begin scenario execution.
   */
  void enableAll();
}
