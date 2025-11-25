package io.pockethive.swarmcontroller.runtime;

import io.pockethive.manager.ports.WorkloadPort;
import io.pockethive.swarmcontroller.infra.docker.WorkloadProvisioner;
import java.util.Map;
import java.util.Objects;

/**
 * Adapter that bridges {@link WorkloadPort} to {@link WorkloadProvisioner}.
 */
public final class DockerWorkloadPortAdapter implements WorkloadPort {

  private final WorkloadProvisioner workloadProvisioner;

  public DockerWorkloadPortAdapter(WorkloadProvisioner workloadProvisioner) {
    this.workloadProvisioner = Objects.requireNonNull(workloadProvisioner, "workloadProvisioner");
  }

  @Override
  public String startWorker(String image, String name, Map<String, String> envVars) {
    return workloadProvisioner.createAndStart(image, name, envVars);
  }

  @Override
  public void stopWorker(String workerId) {
    workloadProvisioner.stopAndRemove(workerId);
  }
}

