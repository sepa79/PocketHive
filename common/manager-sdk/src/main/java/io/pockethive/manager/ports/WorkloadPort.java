package io.pockethive.manager.ports;

import java.util.Map;

/**
 * Port responsible for starting and stopping worker processes/containers.
 */
public interface WorkloadPort {

  /**
   * Create and start a worker instance.
   *
   * @param image   image or binary identifier
   * @param name    logical instance name
   * @param envVars environment variables for the worker
   * @return a runtime-specific container/process id
   */
  String startWorker(String image, String name, Map<String, String> envVars);

  /**
   * Stop and remove a previously started worker instance.
   *
   * @param workerId runtime-specific container/process id
   */
  void stopWorker(String workerId);
}

