package io.pockethive.swarmcontroller.infra.docker;

import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.HostConfig;
import io.pockethive.docker.DockerContainerClient;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Docker-backed {@link WorkloadProvisioner} used by the Swarm Controller.
 */
public final class DockerWorkloadProvisioner implements WorkloadProvisioner {

  private static final Logger log = LoggerFactory.getLogger(DockerWorkloadProvisioner.class);

  private final DockerContainerClient docker;

  public DockerWorkloadProvisioner(DockerContainerClient docker) {
    this.docker = Objects.requireNonNull(docker, "docker");
  }

  @Override
  public String createAndStart(String image, String name, Map<String, String> environment) {
    Objects.requireNonNull(image, "image");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(environment, "environment");
    log.info("creating container {} using image {}", name, image);
    log.info("container env for {}: {}", name, environment);
    String containerId = docker.createContainer(image, environment, name);
    log.info("starting container {} ({})", containerId, name);
    docker.startContainer(containerId);
    return containerId;
  }

  @Override
  public String createAndStart(String image,
                               String name,
                               Map<String, String> environment,
                               List<String> volumes) {
    Objects.requireNonNull(image, "image");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(environment, "environment");
    List<String> safeVolumes = volumes == null ? List.of() : List.copyOf(volumes);

    log.info("creating container {} using image {} with volumes {}", name, image, safeVolumes);
    log.info("container env for {}: {}", name, environment);

    return docker.createAndStartContainer(
        image,
        environment,
        name,
        hostConfig -> {
          if (!safeVolumes.isEmpty()) {
            List<Bind> binds = safeVolumes.stream()
                .filter(v -> v != null && !v.isBlank())
                .map(String::trim)
                .map(Bind::parse)
                .toList();
            if (!binds.isEmpty()) {
              hostConfig.withBinds(binds);
            }
          }
          return hostConfig;
        });
  }

  @Override
  public void stopAndRemove(String containerId) {
    if (containerId == null || containerId.isBlank()) {
      return;
    }
    docker.stopAndRemoveContainer(containerId);
  }
}
