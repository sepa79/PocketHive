package io.pockethive.orchestrator.app;

import io.pockethive.controlplane.spring.ControlPlaneContainerEnvironmentFactory;
import io.pockethive.controlplane.spring.ControlPlaneContainerEnvironmentFactory.PushgatewaySettings;
import io.pockethive.controlplane.spring.ControlPlaneProperties;
import io.pockethive.controlplane.topology.ControlQueueDescriptor;
import io.pockethive.controlplane.topology.SwarmControllerControlPlaneTopologyDescriptor;
import io.pockethive.docker.DockerContainerClient;
import io.pockethive.docker.compute.DockerSingleNodeComputeAdapter;
import io.pockethive.docker.compute.DockerSwarmServiceComputeAdapter;
import io.pockethive.manager.ports.ComputeAdapter;
import io.pockethive.manager.runtime.ComputeAdapterType;
import io.pockethive.manager.runtime.ManagerSpec;
import io.pockethive.orchestrator.config.OrchestratorProperties;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmRegistry;
import io.pockethive.orchestrator.domain.SwarmStatus;
import io.pockethive.orchestrator.domain.SwarmTemplateMetadata;
import io.pockethive.orchestrator.infra.JournalRunMetadataWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.amqp.RabbitProperties;
import org.springframework.stereotype.Service;

@Service
public class ContainerLifecycleManager {
    private static final Logger log = LoggerFactory.getLogger(ContainerLifecycleManager.class);
    private static final String SWARM_CONTROLLER_ROLE = "swarm-controller";
    private static final String SCENARIOS_RUNTIME_DESTINATION = "/app/scenarios-runtime";
    private final DockerContainerClient docker;
    private final ComputeAdapter computeAdapter;
    private final SwarmRegistry registry;
    private final AmqpAdmin amqp;
    private final OrchestratorProperties properties;
    private final ControlPlaneProperties controlPlaneProperties;
    private final RabbitProperties rabbitProperties;
    private final JournalRunMetadataWriter runMetadataWriter;
    @Value("${POCKETHIVE_SCENARIOS_RUNTIME_ROOT:}")
    private String scenariosRuntimeRootSource;
    @Value("${pockethive.journal.sink:postgres}")
    private String journalSink;
    @Value("${spring.datasource.url:}")
    private String datasourceUrl;
    @Value("${spring.datasource.username:}")
    private String datasourceUsername;
    @Value("${spring.datasource.password:}")
    private String datasourcePassword;
    private volatile ComputeAdapterType resolvedAdapterType = ComputeAdapterType.DOCKER_SINGLE;

    public ContainerLifecycleManager(
        DockerContainerClient docker,
        ComputeAdapter computeAdapter,
        SwarmRegistry registry,
        AmqpAdmin amqp,
        OrchestratorProperties properties,
        ControlPlaneProperties controlPlaneProperties,
        RabbitProperties rabbitProperties,
        JournalRunMetadataWriter runMetadataWriter) {
        this.docker = Objects.requireNonNull(docker, "docker");
        this.computeAdapter = Objects.requireNonNull(computeAdapter, "computeAdapter");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.amqp = Objects.requireNonNull(amqp, "amqp");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.controlPlaneProperties = Objects.requireNonNull(controlPlaneProperties, "controlPlaneProperties");
        this.rabbitProperties = Objects.requireNonNull(rabbitProperties, "rabbitProperties");
        this.runMetadataWriter = Objects.requireNonNull(runMetadataWriter, "runMetadataWriter");
        // Initialise the resolved adapter type based on the injected adapter so that
        // status-full events emitted before the first swarm start report the correct mode.
        if (computeAdapter instanceof DockerSwarmServiceComputeAdapter) {
            this.resolvedAdapterType = ComputeAdapterType.SWARM_STACK;
        } else {
            this.resolvedAdapterType = ComputeAdapterType.DOCKER_SINGLE;
        }
    }

    public Swarm startSwarm(String swarmId, String image, String instanceId) {
        return startSwarm(swarmId, image, instanceId, null, false);
    }

    public Swarm startSwarm(String swarmId,
                            String image,
                            String instanceId,
                            SwarmTemplateMetadata templateMetadata) {
        return startSwarm(swarmId, image, instanceId, templateMetadata, false);
    }

    public Swarm startSwarm(String swarmId,
                            String image,
                            String instanceId,
                            SwarmTemplateMetadata templateMetadata,
                            boolean autoPullImages) {
        String resolvedInstance = requireNonBlank(instanceId, "controller instance");
        String resolvedSwarmId = requireNonBlank(swarmId, "swarmId");
        String resolvedImage = resolveImage(image);
        OrchestratorProperties.Pushgateway pushgateway = properties.getMetrics().getPushgateway();
        PushgatewaySettings metrics = new PushgatewaySettings(
            pushgateway.isEnabled(),
            pushgateway.getBaseUrl(),
            pushgateway.getPushRate(),
            pushgateway.getShutdownOperation());
        ControlPlaneContainerEnvironmentFactory.ControllerSettings controllerSettings =
            new ControlPlaneContainerEnvironmentFactory.ControllerSettings(
                properties.getRabbit().getLogsExchange(),
                properties.getRabbit().getLogging().isEnabled(),
                metrics,
                properties.getDocker().getSocketPath(),
                "ph." + resolvedSwarmId,
                "ph." + resolvedSwarmId + ".hive");
        Map<String, String> env = new LinkedHashMap<>(
            ControlPlaneContainerEnvironmentFactory.controllerEnvironment(
                resolvedSwarmId,
                resolvedInstance,
                SWARM_CONTROLLER_ROLE,
                controlPlaneProperties,
                controllerSettings,
                rabbitProperties));
        String runtimeRootSource = scenariosRuntimeRootSource;
        if (runtimeRootSource != null && !runtimeRootSource.isBlank()) {
            env.put("POCKETHIVE_SCENARIOS_RUNTIME_ROOT", runtimeRootSource);
        }
        String resolvedSink = normalizeRuntimeRoot(journalSink);
        if (resolvedSink != null) {
            env.put("POCKETHIVE_JOURNAL_SINK", resolvedSink);
        }
        String resolvedDatasourceUrl = normalizeRuntimeRoot(datasourceUrl);
        if (resolvedDatasourceUrl != null) {
            env.put("SPRING_DATASOURCE_URL", resolvedDatasourceUrl);
        }
        String resolvedDatasourceUsername = normalizeRuntimeRoot(datasourceUsername);
        if (resolvedDatasourceUsername != null) {
            env.put("SPRING_DATASOURCE_USERNAME", resolvedDatasourceUsername);
        }
        String resolvedDatasourcePassword = normalizeRuntimeRoot(datasourcePassword);
        if (resolvedDatasourcePassword != null) {
            env.put("SPRING_DATASOURCE_PASSWORD", resolvedDatasourcePassword);
        }
        String net = docker.resolveControlNetwork();
        if (net != null && !net.isBlank()) {
            env.put("CONTROL_NETWORK", net);
        }
        String dockerSocket = properties.getDocker().getSocketPath();
        env.put("DOCKER_SOCKET_PATH", dockerSocket);
        env.put("DOCKER_HOST", "unix://" + dockerSocket);
        // Propagate the resolved compute adapter choice based on the active adapter
        // instance so the swarm-controller can provision workers with matching mode.
        if (computeAdapter instanceof DockerSwarmServiceComputeAdapter) {
            resolvedAdapterType = ComputeAdapterType.SWARM_STACK;
        } else {
            resolvedAdapterType = ComputeAdapterType.DOCKER_SINGLE;
        }
        env.put("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_DOCKER_COMPUTE_ADAPTER", resolvedAdapterType.name());
        if (autoPullImages) {
            log.info("autoPullImages=true, pulling controller image {} before start", resolvedImage);
            docker.pullImage(resolvedImage);
        }
        String runId = java.util.UUID.randomUUID().toString();
        env.put("POCKETHIVE_JOURNAL_RUN_ID", runId);
        runMetadataWriter.upsertOnSwarmStart(resolvedSwarmId, runId, templateMetadata);
        log.info("launching controller for swarm {} as instance {} using image {} (runId={})",
            resolvedSwarmId, resolvedInstance, resolvedImage, runId);
        log.info("docker env: {}", env);
        java.util.List<String> volumes = new java.util.ArrayList<>();
        volumes.add(dockerSocket + ":" + dockerSocket);
        if (runtimeRootSource != null && !runtimeRootSource.isBlank()) {
            volumes.add(runtimeRootSource + ":" + SCENARIOS_RUNTIME_DESTINATION);
        }
        ManagerSpec managerSpec = new ManagerSpec(
            resolvedInstance,
            resolvedImage,
            java.util.Map.copyOf(env),
            java.util.List.copyOf(volumes));
        String containerId = computeAdapter.startManager(managerSpec);
        log.info("controller container {} ({}) started for swarm {}", containerId, resolvedInstance, resolvedSwarmId);
        Swarm swarm = new Swarm(resolvedSwarmId, resolvedInstance, containerId, runId);
        if (templateMetadata != null) {
            swarm.attachTemplate(templateMetadata);
        }
        registry.register(swarm);
        registry.updateStatus(resolvedSwarmId, SwarmStatus.CREATING);
        return swarm;
    }

    private static String normalizeRuntimeRoot(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Optionally pre-pull all images referenced by a swarm before starting work.
     * <p>
     * When {@code autoPullImages=true} is specified on a start request, the orchestrator can call
     * this helper to ensure that the controller and all bee images are present on the Docker host
     * before the swarm-controller attempts to launch workers. The actual registry and proxy
     * configuration is left to the Docker daemon.
     */
    public void preloadSwarmImages(String swarmId) {
        registry.find(swarmId).ifPresent(swarm -> {
            swarm.controllerImage().ifPresent(image -> {
                String resolved = resolveImage(image);
                log.info("auto-pull: controller image {} (from {}) for swarm {}", resolved, image, swarmId);
                docker.pullImage(resolved);
            });
            swarm.bees().stream()
                .map(bee -> bee.image())
                .filter(image -> image != null && !image.isBlank())
                .map(this::resolveImage)
                .distinct()
                .forEach(resolved -> {
                    log.info("auto-pull: bee image {} for swarm {}", resolved, swarmId);
                    docker.pullImage(resolved);
                });
        });
    }

    private String resolveImage(String image) {
        String trimmed = requireNonBlank(image, "image");
        String prefix = properties.getImageRepositoryPrefix();
        if (prefix == null || prefix.isBlank()) {
            return trimmed;
        }
        // If image already contains a '/', treat it as fully-qualified and leave it unchanged.
        if (trimmed.contains("/")) {
            return trimmed;
        }
        return prefix + "/" + trimmed;
    }

    /**
     * Resolve a bee image for inclusion in a SwarmPlan using the same repository
     * prefix rules as controller images. This keeps the swarm-controller and
     * compute adapters agnostic of registry roots.
     */
    public String resolveImageForPlan(String image) {
        return resolveImage(image);
    }

    public void stopSwarm(String swarmId) {
        registry.find(swarmId).ifPresent(swarm -> {
            SwarmStatus current = swarm.getStatus();
            if (current == SwarmStatus.STOPPING || current == SwarmStatus.STOPPED) {
                log.info("swarm {} already {}", swarmId, current);
                return;
            }
            log.info("marking swarm {} as stopped", swarmId);
            registry.updateStatus(swarmId, SwarmStatus.STOPPING);
            registry.updateStatus(swarmId, SwarmStatus.STOPPED);
        });
    }

    public void removeSwarm(String swarmId) {
        registry.find(swarmId).ifPresent(swarm -> {
            log.info("tearing down controller container {} for swarm {}", swarm.getContainerId(), swarmId);
            registry.updateStatus(swarmId, SwarmStatus.REMOVING);
            computeAdapter.stopManager(swarm.getContainerId());
            // The swarm-controller's own control queue is declared via the manager
            // control-plane topology. Delete it from the orchestrator once the
            // manager has been stopped so we do not race against its AMQP context.
            try {
                String basePrefix = controlPlaneProperties.getControlQueuePrefix();
                SwarmControllerControlPlaneTopologyDescriptor descriptor =
                    new SwarmControllerControlPlaneTopologyDescriptor(swarmId, basePrefix);
                String controllerQueue = descriptor.controlQueue(swarm.getInstanceId())
                    .map(ControlQueueDescriptor::name)
                    .orElse(null);
                if (controllerQueue != null && !controllerQueue.isBlank()) {
                    log.info("deleting swarm-controller control queue {}", controllerQueue);
                    amqp.deleteQueue(controllerQueue);
                }
            } catch (Exception ex) {
                log.warn("Failed to delete swarm-controller control queue for swarm {}: {}", swarmId, ex.getMessage());
            }
            amqp.deleteQueue("ph." + swarmId + ".gen");
            amqp.deleteQueue("ph." + swarmId + ".mod");
            amqp.deleteQueue("ph." + swarmId + ".final");
            registry.updateStatus(swarmId, SwarmStatus.REMOVED);
            swarm.clearTemplate();
            registry.remove(swarmId);
        });
    }

    private static String requireNonBlank(String value, String description) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(description + " must not be null or blank");
        }
        return value;
    }

    ComputeAdapterType currentComputeAdapterType() {
        return resolvedAdapterType;
    }
}
