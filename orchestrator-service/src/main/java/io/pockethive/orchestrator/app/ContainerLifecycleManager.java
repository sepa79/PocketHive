package io.pockethive.orchestrator.app;

import io.pockethive.controlplane.spring.ControlPlaneContainerEnvironmentFactory;
import io.pockethive.controlplane.spring.ControlPlaneContainerEnvironmentFactory.MetricsSettings;
import io.pockethive.controlplane.spring.ControlPlaneProperties;
import io.pockethive.controlplane.topology.ControlQueueDescriptor;
import io.pockethive.controlplane.topology.SwarmControllerControlPlaneTopologyDescriptor;
import io.pockethive.docker.DockerContainerClient;
import io.pockethive.docker.compute.DockerSwarmServiceComputeAdapter;
import io.pockethive.manager.ports.ComputeAdapter;
import io.pockethive.manager.runtime.ComputeAdapterType;
import io.pockethive.manager.runtime.ManagerSpec;
import io.pockethive.orchestrator.config.OrchestratorProperties;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmStore;
import io.pockethive.orchestrator.domain.SwarmTemplateMetadata;
import io.pockethive.orchestrator.infra.JournalRunMetadataWriter;
import io.pockethive.orchestrator.runtime.RuntimeCleanupPorts.RuntimeOwnershipManifestStore;
import io.pockethive.orchestrator.runtime.RuntimeOwnershipManifest;
import io.pockethive.sink.clickhouse.ClickHouseSinkProperties;
import io.pockethive.swarm.model.NetworkMode;
import io.pockethive.swarm.model.Bee;
import io.pockethive.swarm.model.SwarmStartupArtifactContract;
import io.pockethive.swarm.model.SwarmStartupArtifactReference;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final SwarmStore store;
    private final AmqpAdmin amqp;
    private final OrchestratorProperties properties;
    private final ControlPlaneProperties controlPlaneProperties;
    private final RabbitProperties rabbitProperties;
    private final JournalRunMetadataWriter runMetadataWriter;
    private final ClickHouseSinkProperties clickHouseSink;
    private final RuntimeOwnershipManifestStore manifestStore;
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
    @Value("${POCKETHIVE_DOCKER_SWARM_PLACEMENT_CONSTRAINTS:}")
    private String swarmPlacementConstraints;
    private volatile ComputeAdapterType resolvedAdapterType = ComputeAdapterType.DOCKER_SINGLE;

    @Autowired
    public ContainerLifecycleManager(
        DockerContainerClient docker,
        ComputeAdapter computeAdapter,
        SwarmStore store,
        AmqpAdmin amqp,
        OrchestratorProperties properties,
        ControlPlaneProperties controlPlaneProperties,
        RabbitProperties rabbitProperties,
        JournalRunMetadataWriter runMetadataWriter,
        ClickHouseSinkProperties clickHouseSink,
        RuntimeOwnershipManifestStore manifestStore) {
        this.docker = Objects.requireNonNull(docker, "docker");
        this.computeAdapter = Objects.requireNonNull(computeAdapter, "computeAdapter");
        this.store = Objects.requireNonNull(store, "store");
        this.amqp = Objects.requireNonNull(amqp, "amqp");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.controlPlaneProperties = Objects.requireNonNull(controlPlaneProperties, "controlPlaneProperties");
        this.rabbitProperties = Objects.requireNonNull(rabbitProperties, "rabbitProperties");
        this.runMetadataWriter = Objects.requireNonNull(runMetadataWriter, "runMetadataWriter");
        this.clickHouseSink = Objects.requireNonNull(clickHouseSink, "clickHouseSink");
        this.manifestStore = Objects.requireNonNull(manifestStore, "manifestStore");
        this.resolvedAdapterType = requireConcreteAdapterType(computeAdapter.type());
    }

    public ContainerLifecycleManager(
        DockerContainerClient docker,
        ComputeAdapter computeAdapter,
        SwarmStore store,
        AmqpAdmin amqp,
        OrchestratorProperties properties,
        ControlPlaneProperties controlPlaneProperties,
        RabbitProperties rabbitProperties,
        JournalRunMetadataWriter runMetadataWriter,
        ClickHouseSinkProperties clickHouseSink) {
        this(
            docker,
            computeAdapter,
            store,
            amqp,
            properties,
            controlPlaneProperties,
            rabbitProperties,
            runMetadataWriter,
            clickHouseSink,
            new RuntimeOwnershipManifestStore() {
                @Override
                public void save(RuntimeOwnershipManifest manifest) {
                }

                @Override
                public java.util.Optional<RuntimeOwnershipManifest> find(String swarmId, String runId) {
                    return java.util.Optional.empty();
                }

                @Override
                public java.util.Optional<RuntimeOwnershipManifest> findLatest(String swarmId) {
                    return java.util.Optional.empty();
                }
            });
    }

    public Swarm startSwarm(String swarmId,
                            String image,
                            String instanceId,
                            SwarmTemplateMetadata templateMetadata,
                            boolean autoPullImages,
                            String sutId,
                            NetworkMode networkMode,
                            String networkProfileId,
                            SwarmStartupArtifactReference startupArtifact) {
        Objects.requireNonNull(templateMetadata, "templateMetadata");
        Objects.requireNonNull(startupArtifact, "startupArtifact");
        String resolvedInstance = requireNonBlank(instanceId, "controller instance");
        String resolvedSwarmId = requireNonBlank(swarmId, "swarmId");
        String resolvedImage = resolveImage(image);
        String runId = java.util.UUID.randomUUID().toString();
        MetricsSettings metrics = metricsSettings(properties.getMetrics());
        ControlPlaneContainerEnvironmentFactory.ControllerSettings controllerSettings =
            new ControlPlaneContainerEnvironmentFactory.ControllerSettings(
                metrics,
                runId,
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
        applyClickHouseSinkEnv(env);
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
        resolvedAdapterType = requireConcreteAdapterType(computeAdapter.type());
        env.put("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_DOCKER_COMPUTE_ADAPTER", resolvedAdapterType.name());
        putEnvIfMissing(env, DockerSwarmServiceComputeAdapter.PLACEMENT_CONSTRAINTS_ENV, normalizeRuntimeRoot(swarmPlacementConstraints));
        env.put("POCKETHIVE_RUNTIME_IMAGE", resolvedImage);
        env.put("POCKETHIVE_TEMPLATE_ID", requireText(templateMetadata.templateId(), "templateId"));
        env.put(
            SwarmStartupArtifactContract.PATH_ENV,
            startupArtifact.path());
        env.put(
            SwarmStartupArtifactContract.SHA256_ENV,
            startupArtifact.sha256());
        env.put("POCKETHIVE_RUNTIME_STACK_NAME", "ph-" + resolvedSwarmId.toLowerCase(java.util.Locale.ROOT));
        putEnvIfMissing(env, "POCKETHIVE_SUT_ID", normalizeRuntimeRoot(sutId));
        env.put("POCKETHIVE_NETWORK_MODE", NetworkMode.directIfNull(networkMode).name());
        putEnvIfMissing(env, "POCKETHIVE_NETWORK_PROFILE_ID", normalizeRuntimeRoot(networkProfileId));
        if (autoPullImages) {
            log.info("autoPullImages=true, pulling controller image {} before start", resolvedImage);
            docker.pullImage(resolvedImage);
        }
        env.put("POCKETHIVE_JOURNAL_RUN_ID", runId);
        runMetadataWriter.upsertOnSwarmStart(resolvedSwarmId, runId, templateMetadata);
        log.info("launching controller for swarm {} as instance {} using image {} (runId={})",
            resolvedSwarmId, resolvedInstance, resolvedImage, runId);
        log.info("docker env: {}", redactEnv(env));
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
        swarm.attachStartupArtifact(startupArtifact);
        store.register(swarm);
        writeRuntimeOwnershipManifest(
            resolvedSwarmId,
            runId,
            resolvedInstance,
            resolvedImage,
            containerId,
            templateMetadata,
            controllerSettings);
        return swarm;
    }

    private static MetricsSettings metricsSettings(OrchestratorProperties.Metrics metrics) {
        return new MetricsSettings(
            metrics.getAdapter(),
            metrics.getPublishInterval(),
            metrics.getClickHouse());
    }

    private void writeRuntimeOwnershipManifest(String swarmId,
                                               String runId,
                                               String controllerInstance,
                                               String controllerImage,
                                               String controllerRuntimeId,
                                               SwarmTemplateMetadata templateMetadata,
                                               ControlPlaneContainerEnvironmentFactory.ControllerSettings controllerSettings) {
        String controllerQueue = new SwarmControllerControlPlaneTopologyDescriptor(
            swarmId,
            controlPlaneProperties.getControlQueuePrefix())
            .controlQueue(controllerInstance)
            .map(ControlQueueDescriptor::name)
            .orElse(null);
        List<String> workQueues = controllerSettings.trafficQueueNames(workQueueSuffixes(templateMetadata.bees()));
        List<String> controlQueues = controllerQueue == null || controllerQueue.isBlank()
            ? List.of()
            : List.of(controllerQueue);
        RuntimeOwnershipManifest manifest = new RuntimeOwnershipManifest(
            swarmId,
            runId,
            templateMetadata.templateId(),
            resolvedAdapterType.name(),
            java.time.Instant.now(),
            List.of(new RuntimeOwnershipManifest.RuntimeObject(
                controllerRuntimeId,
                resolvedAdapterType == ComputeAdapterType.SWARM_STACK ? "service" : "container",
                "manager",
                SWARM_CONTROLLER_ROLE,
                controllerInstance,
                controllerImage)),
            new RuntimeOwnershipManifest.RabbitResources(
                controlQueues,
                workQueues,
                List.of(controllerSettings.trafficHiveExchange())));
        manifestStore.save(manifest);
    }

    private static Set<String> workQueueSuffixes(List<Bee> bees) {
        Set<String> suffixes = new LinkedHashSet<>();
        for (Bee bee : bees == null ? List.<Bee>of() : bees) {
            if (bee == null || bee.work() == null) {
                continue;
            }
            suffixes.addAll(bee.work().in().values());
            suffixes.addAll(bee.work().out().values());
        }
        suffixes.removeIf(value -> value == null || value.isBlank());
        return suffixes;
    }

    private void applyClickHouseSinkEnv(Map<String, String> targetEnv) {
        if (!clickHouseSink.configured()) {
            return;
        }
        putEnvIfMissing(targetEnv, "POCKETHIVE_SINK_CLICKHOUSE_ENDPOINT", clickHouseSink.getEndpoint());
        putEnvIfMissing(targetEnv, "POCKETHIVE_SINK_CLICKHOUSE_TABLE", clickHouseSink.getTable());
        putEnvIfMissing(targetEnv, "POCKETHIVE_SINK_CLICKHOUSE_USERNAME", clickHouseSink.getUsername());
        putEnvIfMissing(targetEnv, "POCKETHIVE_SINK_CLICKHOUSE_PASSWORD", clickHouseSink.getPassword());
        putEnvIfMissing(targetEnv, "POCKETHIVE_SINK_CLICKHOUSE_CONNECT_TIMEOUT_MS",
            Integer.toString(clickHouseSink.getConnectTimeoutMs()));
        putEnvIfMissing(targetEnv, "POCKETHIVE_SINK_CLICKHOUSE_READ_TIMEOUT_MS",
            Integer.toString(clickHouseSink.getReadTimeoutMs()));
        putEnvIfMissing(targetEnv, "POCKETHIVE_SINK_CLICKHOUSE_BATCH_SIZE",
            Integer.toString(clickHouseSink.getBatchSize()));
        putEnvIfMissing(targetEnv, "POCKETHIVE_SINK_CLICKHOUSE_FLUSH_INTERVAL_MS",
            Integer.toString(clickHouseSink.getFlushIntervalMs()));
        putEnvIfMissing(targetEnv, "POCKETHIVE_SINK_CLICKHOUSE_MAX_BUFFERED_EVENTS",
            Integer.toString(clickHouseSink.getMaxBufferedEvents()));
    }

    private static Map<String, String> redactEnv(Map<String, String> env) {
        Map<String, String> redacted = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : env.entrySet()) {
            String key = entry.getKey();
            if (key == null) {
                continue;
            }
            if (isSensitiveKey(key)) {
                redacted.put(key, "***");
            } else {
                redacted.put(key, entry.getValue());
            }
        }
        return redacted;
    }

    private static boolean isSensitiveKey(String key) {
        String upper = key.toUpperCase(Locale.ROOT);
        return upper.contains("PASSWORD") || upper.contains("SECRET") || upper.contains("TOKEN");
    }

    private static void putEnvIfMissing(Map<String, String> env, String key, String value) {
        if (env.containsKey(key)) {
            return;
        }
        if (value == null) {
            return;
        }
        String text = value.trim();
        if (!text.isBlank()) {
            env.put(key, text);
        }
    }

    private static String normalizeRuntimeRoot(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be null or blank");
        }
        return value.trim();
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
        store.find(swarmId).ifPresent(swarm -> {
            String controllerImage = swarm.controllerImage();
            if (controllerImage != null && !controllerImage.isBlank()) {
                String resolved = resolveImage(controllerImage);
                log.info("auto-pull: controller image {} (from {}) for swarm {}", resolved, controllerImage, swarmId);
                docker.pullImage(resolved);
            }
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

    public ControllerRuntimeRemoval removeControllerRuntime(String swarmId) {
        Swarm swarm = store.find(swarmId)
            .orElseThrow(() -> new IllegalStateException("Swarm is not registered: " + swarmId));
        var removed = new java.util.ArrayList<io.pockethive.swarm.model.lifecycle.RemoveResource>();
        var remaining = new java.util.ArrayList<io.pockethive.swarm.model.lifecycle.RemoveResource>();
        var errors = new java.util.ArrayList<io.pockethive.swarm.model.lifecycle.RemoveError>();

        var controller = new io.pockethive.swarm.model.lifecycle.RemoveResource(
            io.pockethive.swarm.model.lifecycle.RemoveResourceType.CONTROLLER_RUNTIME,
            swarm.getContainerId());
        try {
            log.info("tearing down controller runtime {} for swarm {}", swarm.getContainerId(), swarmId);
            computeAdapter.stopManager(swarm.getContainerId());
            removed.add(controller);
        } catch (RuntimeException failure) {
            remaining.add(controller);
            errors.add(removeError(failure, controller));
        }

        String basePrefix = controlPlaneProperties.getControlQueuePrefix();
        String controllerQueue = new SwarmControllerControlPlaneTopologyDescriptor(swarmId, basePrefix)
            .controlQueue(swarm.getInstanceId())
            .map(ControlQueueDescriptor::name)
            .orElseThrow(() -> new IllegalStateException("Controller control queue is not defined"));
        var queue = new io.pockethive.swarm.model.lifecycle.RemoveResource(
            io.pockethive.swarm.model.lifecycle.RemoveResourceType.RABBIT_QUEUE,
            controllerQueue);
        try {
            log.info("deleting swarm-controller control queue {}", controllerQueue);
            amqp.deleteQueue(controllerQueue);
            removed.add(queue);
        } catch (RuntimeException failure) {
            remaining.add(queue);
            errors.add(removeError(failure, queue));
        }
        return new ControllerRuntimeRemoval(removed, remaining, errors);
    }

    private static io.pockethive.swarm.model.lifecycle.RemoveError removeError(
        RuntimeException failure,
        io.pockethive.swarm.model.lifecycle.RemoveResource resource) {
        return new io.pockethive.swarm.model.lifecycle.RemoveError(
            failure.getClass().getSimpleName(),
            java.util.Objects.toString(failure.getMessage(), failure.getClass().getName()),
            resource);
    }

    public record ControllerRuntimeRemoval(
        java.util.List<io.pockethive.swarm.model.lifecycle.RemoveResource> removedResources,
        java.util.List<io.pockethive.swarm.model.lifecycle.RemoveResource> remainingResources,
        java.util.List<io.pockethive.swarm.model.lifecycle.RemoveError> errors) {
        public ControllerRuntimeRemoval {
            removedResources = java.util.List.copyOf(removedResources);
            remainingResources = java.util.List.copyOf(remainingResources);
            errors = java.util.List.copyOf(errors);
        }

        public boolean succeeded() {
            return remainingResources.isEmpty() && errors.isEmpty();
        }
    }

    private static String requireNonBlank(String value, String description) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(description + " must not be null or blank");
        }
        return value;
    }

    public ComputeAdapterType currentComputeAdapterType() {
        return resolvedAdapterType;
    }

    private static ComputeAdapterType requireConcreteAdapterType(ComputeAdapterType adapterType) {
        if (adapterType == null || adapterType == ComputeAdapterType.AUTO) {
            throw new IllegalStateException("ComputeAdapter must expose a concrete adapter type");
        }
        return adapterType;
    }
}
