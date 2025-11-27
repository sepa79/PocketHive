package io.pockethive.manager.ports;

import io.pockethive.manager.runtime.ManagerSpec;
import io.pockethive.manager.runtime.WorkerSpec;
import java.util.List;

/**
 * Abstraction over the underlying compute platform used to run managers and workers.
 * <p>
 * Implementations may map these operations to single-node Docker containers,
 * Docker Swarm services, Kubernetes deployments, or other runtimes. The interface
 * is intentionally minimal and free of transport-specific types.
 */
public interface ComputeAdapter {

  /**
   * Start a manager/controller process for the given specification.
   *
   * @param spec manager description (id, image, environment, volumes)
   * @return runtime-specific identifier for the started manager
   */
  String startManager(ManagerSpec spec);

  /**
   * Stop and remove a previously started manager/controller.
   *
   * @param managerId runtime-specific identifier returned by {@link #startManager(ManagerSpec)}
   */
  void stopManager(String managerId);

  /**
   * Reconcile the set of workers for a given topology or swarm identifier.
   * <p>
   * Implementations are expected to be idempotent: applying the same set twice
   * should not create duplicate workers.
   *
   * @param topologyId logical id for the managed topology (for example swarm id)
   * @param workers    desired worker specifications
   */
  void applyWorkers(String topologyId, List<WorkerSpec> workers);

  /**
   * Remove all workers associated with the given topology identifier.
   *
   * @param topologyId logical id for the managed topology (for example swarm id)
   */
  void removeWorkers(String topologyId);
}

