package io.pockethive.docker.compute;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateServiceResponse;
import com.github.dockerjava.api.model.ContainerSpec;
import com.github.dockerjava.api.model.Mount;
import com.github.dockerjava.api.model.MountType;
import com.github.dockerjava.api.model.NetworkAttachmentConfig;
import com.github.dockerjava.api.model.ServiceModeConfig;
import com.github.dockerjava.api.model.ServicePlacement;
import com.github.dockerjava.api.model.ServiceReplicatedModeOptions;
import com.github.dockerjava.api.model.ServiceSpec;
import com.github.dockerjava.api.model.TaskSpec;
import io.pockethive.manager.ports.ComputeAdapter;
import io.pockethive.manager.runtime.ManagerSpec;
import io.pockethive.manager.runtime.WorkerSpec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link ComputeAdapter} implementation backed by Docker Swarm services.
 * <p>
 * This adapter is deliberately minimal: it creates one service per manager or worker
 * with a single replica and does not yet attempt full reconciliation beyond basic
 * "create once, remove on teardown" semantics. It is intended as a shared building
 * block for both the Orchestrator and Swarm Controller when running against a Swarm
 * cluster.
 */
public final class DockerSwarmServiceComputeAdapter implements ComputeAdapter {

  private static final Logger log = LoggerFactory.getLogger(DockerSwarmServiceComputeAdapter.class);

  private final DockerClient dockerClient;
  private final Supplier<String> controlNetworkSupplier;

  // Manager id (logical) -> service id (runtime)
  private final Map<String, String> managerServices = new ConcurrentHashMap<>();

  // topology id -> service ids (runtime) for workers
  private final Map<String, List<String>> workerServicesByTopology = new ConcurrentHashMap<>();

  public DockerSwarmServiceComputeAdapter(DockerClient dockerClient,
                                          Supplier<String> controlNetworkSupplier) {
    this.dockerClient = Objects.requireNonNull(dockerClient, "dockerClient");
    this.controlNetworkSupplier = controlNetworkSupplier != null
        ? controlNetworkSupplier
        : () -> null;
  }

  @Override
  public String startManager(ManagerSpec spec) {
    Objects.requireNonNull(spec, "spec");
    String id = requireNonBlank(spec.id(), "spec.id");
    String image = requireNonBlank(spec.image(), "spec.image");
    Map<String, String> env = spec.environment() == null ? Map.of() : Map.copyOf(spec.environment());
    List<String> volumes = spec.volumes() == null ? List.of() : List.copyOf(spec.volumes());
    String swarmId = extractSwarmId(env);

    log.info("Creating Swarm service for manager {} using image {} in swarm {}", id, image, swarmId);
    ServiceSpec serviceSpec = buildServiceSpec(id, image, env, volumes, swarmId, true);
    CreateServiceResponse response = dockerClient.createServiceCmd(serviceSpec).exec();
    String serviceId = response.getId();
    managerServices.put(id, serviceId);
    return serviceId;
  }

  @Override
  public void stopManager(String managerId) {
    if (managerId == null || managerId.isBlank()) {
      return;
    }
    String serviceId = managerServices.remove(managerId);
    // If the caller passed the raw service id, fall back to using it directly.
    if (serviceId == null) {
      serviceId = managerId;
    }
    log.info("Removing Swarm service for manager {}", serviceId);
    try {
      dockerClient.removeServiceCmd(serviceId).exec();
    } catch (RuntimeException e) {
      log.warn("Failed to remove manager service {}: {}", serviceId, e.getMessage());
    }
  }

  @Override
  public void applyWorkers(String topologyId, List<WorkerSpec> workers) {
    if (workers == null || workers.isEmpty()) {
      return;
    }
    String resolvedTopology = requireNonBlank(topologyId, "topologyId");
    List<String> serviceIds = workerServicesByTopology.computeIfAbsent(
        resolvedTopology, id -> new CopyOnWriteArrayList<>());
    for (WorkerSpec worker : workers) {
      if (worker == null) {
        continue;
      }
      String workerId = requireNonBlank(worker.id(), "worker.id");
      String image = requireNonBlank(worker.image(), "worker.image");
      Map<String, String> env = worker.environment() == null
          ? Map.of()
          : Map.copyOf(worker.environment());
      List<String> volumes = worker.volumes() == null
          ? List.of()
          : List.copyOf(worker.volumes());
      log.info("Creating Swarm service for worker {} in topology {} using image {}",
          workerId, resolvedTopology, image);
      ServiceSpec spec = buildServiceSpec(workerId, image, env, volumes, resolvedTopology, false);
      CreateServiceResponse response = dockerClient.createServiceCmd(spec).exec();
      serviceIds.add(response.getId());
    }
  }

  @Override
  public void removeWorkers(String topologyId) {
    if (topologyId == null || topologyId.isBlank()) {
      return;
    }
    List<String> ids = workerServicesByTopology.remove(topologyId);
    if (ids == null || ids.isEmpty()) {
      return;
    }
    // Stop in reverse order, mirroring the container-based adapter.
    List<String> copy = new ArrayList<>(ids);
    Collections.reverse(copy);
    for (String id : copy) {
      try {
        log.info("Removing Swarm service {} for topology {}", id, topologyId);
        dockerClient.removeServiceCmd(id).exec();
      } catch (RuntimeException e) {
        log.warn("Failed to remove worker service {}: {}", id, e.getMessage());
      }
    }
  }

  private ServiceSpec buildServiceSpec(String name,
                                       String image,
                                       Map<String, String> env,
                                       List<String> volumes,
                                       String swarmId,
                                       boolean managerOnly) {
    ContainerSpec containerSpec = new ContainerSpec()
        .withImage(image)
        .withEnv(toEnvList(env));

    List<Mount> mounts = toMounts(volumes);
    if (!mounts.isEmpty()) {
      containerSpec = containerSpec.withMounts(mounts);
    }

    TaskSpec taskSpec = new TaskSpec()
        .withContainerSpec(containerSpec);

    if (managerOnly) {
      ServicePlacement placement = new ServicePlacement()
          .withConstraints(List.of("node.role == manager"));
      taskSpec = taskSpec.withPlacement(placement);
    }

    ServiceModeConfig mode = new ServiceModeConfig()
        .withReplicated(new ServiceReplicatedModeOptions().withReplicas(1));

    Map<String, String> labels = new HashMap<>();
    String stackNamespace = stackNamespace(swarmId);
    labels.put("com.docker.stack.namespace", stackNamespace);
    labels.put("ph.swarmId", swarmId);

    ServiceSpec serviceSpec = new ServiceSpec()
        .withName(name)
        .withTaskTemplate(taskSpec)
        .withMode(mode)
        .withLabels(labels);

    String network = controlNetworkSupplier.get();
    if (network != null && !network.isBlank()) {
      NetworkAttachmentConfig attachment = new NetworkAttachmentConfig()
          .withTarget(network);
      serviceSpec = serviceSpec.withNetworks(List.of(attachment));
    }

    return serviceSpec;
  }

  private List<String> toEnvList(Map<String, String> env) {
    if (env == null || env.isEmpty()) {
      return List.of();
    }
    return env.entrySet().stream()
        .map(e -> e.getKey() + "=" + e.getValue())
        .toList();
  }

  private List<Mount> toMounts(List<String> volumes) {
    if (volumes == null || volumes.isEmpty()) {
      return List.of();
    }
    List<Mount> mounts = new ArrayList<>();
    for (String spec : volumes) {
      if (spec == null || spec.isBlank()) {
        continue;
      }
      String trimmed = spec.trim();
      // Basic "host:container[:mode]" parsing. More advanced options can be added later.
      String[] parts = trimmed.split(":");
      if (parts.length < 2) {
        continue;
      }
      String source = parts[0];
      String target = parts[1];
      Mount mount = new Mount()
          .withType(MountType.BIND)
          .withSource(source)
          .withTarget(target);
      mounts.add(mount);
    }
    return mounts;
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }

  private static String extractSwarmId(Map<String, String> env) {
    if (env == null || env.isEmpty()) {
      throw new IllegalArgumentException("manager environment must contain POCKETHIVE_CONTROL_PLANE_SWARM_ID");
    }
    String swarmId = env.get("POCKETHIVE_CONTROL_PLANE_SWARM_ID");
    if (swarmId == null || swarmId.isBlank()) {
      throw new IllegalArgumentException("POCKETHIVE_CONTROL_PLANE_SWARM_ID must not be null or blank");
    }
    return swarmId;
  }

  private static String stackNamespace(String swarmId) {
    String normalized = requireNonBlank(swarmId, "swarmId");
    return "ph-" + normalized.toLowerCase(Locale.ROOT);
  }
}
