package io.pockethive.docker.compute;

import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.HostConfig;
import io.pockethive.docker.DockerContainerClient;
import io.pockethive.manager.ports.ComputeAdapter;
import io.pockethive.manager.runtime.ManagerSpec;
import io.pockethive.manager.runtime.WorkerSpec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link ComputeAdapter} backed by {@link DockerContainerClient} on a single Docker host.
 * <p>
 * This adapter maps managers and workers to individual containers and keeps a small
 * in-memory index of worker containers per topology so {@link #removeWorkers(String)}
 * can tear them down in a predictable order.
 */
public final class DockerSingleNodeComputeAdapter implements ComputeAdapter {

  private static final Logger log = LoggerFactory.getLogger(DockerSingleNodeComputeAdapter.class);

  private final DockerContainerClient docker;
  private final Map<String, List<String>> workerContainersByTopology = new LinkedHashMap<>();

  public DockerSingleNodeComputeAdapter(DockerContainerClient docker) {
    this.docker = Objects.requireNonNull(docker, "docker");
  }

  @Override
  public String startManager(ManagerSpec spec) {
    Objects.requireNonNull(spec, "spec");
    String id = requireNonBlank(spec.id(), "spec.id");
    String image = requireNonBlank(spec.image(), "spec.image");
    Map<String, String> env = spec.environment() == null ? Map.of() : Map.copyOf(spec.environment());
    List<String> volumes = safeVolumes(spec.volumes());

    log.info("starting manager {} using image {} with volumes {}", id, image, volumes);
    UnaryOperator<HostConfig> hostConfigCustomizer = hostConfig -> {
      applyBinds(hostConfig, volumes);
      return hostConfig;
    };
    return docker.createAndStartContainer(image, env, id, hostConfigCustomizer);
  }

  @Override
  public void stopManager(String managerId) {
    if (managerId == null || managerId.isBlank()) {
      return;
    }
    log.info("stopping manager container {}", managerId);
    docker.stopAndRemoveContainer(managerId);
  }

  @Override
  public synchronized void applyWorkers(String topologyId, List<WorkerSpec> workers) {
    if (workers == null || workers.isEmpty()) {
      return;
    }
    String resolvedTopology = requireNonBlank(topologyId, "topologyId");
    List<String> containerIds = workerContainersByTopology.computeIfAbsent(
        resolvedTopology, id -> new ArrayList<>());

    for (WorkerSpec worker : workers) {
      if (worker == null) {
        continue;
      }
      String name = requireNonBlank(worker.id(), "worker.id");
      String image = requireNonBlank(worker.image(), "worker.image");
      Map<String, String> env = worker.environment() == null
          ? Map.of()
          : Map.copyOf(worker.environment());
      List<String> volumes = safeVolumes(worker.volumes());

      log.info("applyWorkers: starting worker {} for topology {} using image {} with volumes {}",
          name, resolvedTopology, image, volumes);

      UnaryOperator<HostConfig> hostConfigCustomizer = hostConfig -> {
        applyBinds(hostConfig, volumes);
        return hostConfig;
      };
      String containerId = docker.createAndStartContainer(image, env, name, hostConfigCustomizer);
      containerIds.add(containerId);
    }
  }

  @Override
  public synchronized void removeWorkers(String topologyId) {
    if (topologyId == null || topologyId.isBlank()) {
      return;
    }
    List<String> ids = workerContainersByTopology.remove(topologyId);
    if (ids == null || ids.isEmpty()) {
      return;
    }
    // Stop in reverse order (last started, first stopped), mirroring the
    // previous Swarm Controller behaviour.
    List<String> copy = new ArrayList<>(ids);
    Collections.reverse(copy);
    for (String id : copy) {
      log.info("removeWorkers: stopping container {} for topology {}", id, topologyId);
      docker.stopAndRemoveContainer(id);
    }
  }

  private static List<String> safeVolumes(List<String> raw) {
    if (raw == null || raw.isEmpty()) {
      return List.of();
    }
    List<String> result = new ArrayList<>();
    for (Object v : raw) {
      if (!(v instanceof String s)) {
        continue;
      }
      String trimmed = s.trim();
      if (!trimmed.isEmpty()) {
        result.add(trimmed);
      }
    }
    return List.copyOf(result);
  }

  private static void applyBinds(HostConfig hostConfig, List<String> volumes) {
    if (volumes == null || volumes.isEmpty()) {
      return;
    }
    List<Bind> binds = volumes.stream()
        .filter(v -> v != null && !v.isBlank())
        .map(String::trim)
        .map(Bind::parse)
        .toList();
    if (!binds.isEmpty()) {
      hostConfig.withBinds(binds);
    }
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}

