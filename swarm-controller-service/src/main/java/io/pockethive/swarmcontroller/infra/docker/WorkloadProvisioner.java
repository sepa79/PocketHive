package io.pockethive.swarmcontroller.infra.docker;

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
   * Stop and remove the container identified by the given id.
   */
  void stopAndRemove(String containerId);
}

