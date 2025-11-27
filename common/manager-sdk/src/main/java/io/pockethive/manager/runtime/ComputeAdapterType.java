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
   * Docker Swarm services (or an equivalent service-style abstraction).
   * <p>
   * The initial implementations may delegate to {@link #DOCKER_SINGLE}
   * while service-based adapters are being brought up.
   */
  SWARM_SERVICE;

  /**
   * Return {@link #DOCKER_SINGLE} when the provided value is {@code null},
   * otherwise return the value as-is.
   */
  public static ComputeAdapterType defaulted(ComputeAdapterType value) {
    return value == null ? DOCKER_SINGLE : value;
  }
}
