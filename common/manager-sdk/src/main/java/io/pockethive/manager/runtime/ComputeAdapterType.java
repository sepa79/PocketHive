package io.pockethive.manager.runtime;

/**
 * Shared enumeration describing which {@code ComputeAdapter} implementation
 * should be used for managers and workers.
 * <p>
 * This type lives in the manager SDK so that orchestrators, swarm controllers
 * and any future control-plane components can make the same choice without
 * duplicating enums in their own config models.
 */
public enum ComputeAdapterType {

  /**
   * Single-node Docker engine using plain containers.
   */
  DOCKER_SINGLE,

  /**
   * Automatically detect the appropriate adapter for the environment.
   * <p>
   * For now this is only honoured by the orchestrator; swarm controllers
   * expect an explicit adapter type.
   */
  AUTO,

  /**
   * Docker Swarm stack-style grouping (controller + bees).
   * <p>
   * Initially this may be backed by the same implementation as a
   * Swarm service-based adapter until a dedicated stack adapter is
   * introduced; the separate enum value allows configuration and
   * diagnostics to distinguish the intent.
   */
  SWARM_STACK;

  /**
   * Return {@link #DOCKER_SINGLE} when the provided value is {@code null},
   * otherwise return the value as-is.
   */
  public static ComputeAdapterType defaulted(ComputeAdapterType value) {
    return value == null ? DOCKER_SINGLE : value;
  }
}
