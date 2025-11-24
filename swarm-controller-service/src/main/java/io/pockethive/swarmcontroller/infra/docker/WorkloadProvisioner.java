package io.pockethive.swarmcontroller.infra.docker;

import java.util.List;
import java.util.Map;

/**
 * Port for provisioning workload containers for a swarm.
 */
public interface WorkloadProvisioner {

  /**
   * Create and start a container for the given image, assigning the provided name and environment.
   *
   * @return the created container id
   */
  String createAndStart(String image, String name, Map<String, String> environment);

  /**
   * Create and start a container with explicit volume bindings.
   * <p>
   * Default implementation delegates to {@link #createAndStart(String, String, Map)} and ignores
   * the {@code volumes} list. Implementations that support volume bindings can override this
   * method to honour the requested mounts.
   *
   * @param image       docker image reference
   * @param name        container name
   * @param environment environment variables
   * @param volumes     list of Docker volume specs (e.g. {@code /host:/container:ro})
   * @return the created container id
   */
  default String createAndStart(String image,
                                String name,
                                Map<String, String> environment,
                                List<String> volumes) {
    return createAndStart(image, name, environment);
  }

  /**
   * Stop and remove the container identified by the given id.
   */
  void stopAndRemove(String containerId);
}
