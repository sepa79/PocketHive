package io.pockethive.orchestrator.app;

import io.pockethive.controlplane.filesystem.RuntimeFilesystemMount;
import io.pockethive.controlplane.spring.ControlPlaneContainerEnvironmentFactory;
import io.pockethive.controlplane.spring.ControlPlaneProperties;
import io.pockethive.controlplane.spring.ControllerSettings;
import io.pockethive.controlplane.spring.MetricsSettings;
import io.pockethive.controlplane.topology.ControlQueueDescriptor;
import io.pockethive.controlplane.topology.SwarmControllerControlPlaneTopologyDescriptor;
import io.pockethive.manager.ports.ComputeHost;
import io.pockethive.docker.DockerControllerEnvironment;
import io.pockethive.docker.DockerRuntimeNames;
import io.pockethive.manager.ports.ComputeAdapter;
import io.pockethive.manager.runtime.ComputeAdapterType;
import io.pockethive.manager.runtime.ManagerSpec;
import io.pockethive.orchestrator.config.OrchestratorMetricsProperties;
import io.pockethive.orchestrator.config.OrchestratorProperties;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmStore;
import io.pockethive.orchestrator.domain.SwarmTemplateMetadata;
import io.pockethive.orchestrator.app.JournalRunRegistration;
import io.pockethive.orchestrator.runtime.RuntimeCleanupPorts.RuntimeOwnershipManifestStore;
import io.pockethive.orchestrator.runtime.RuntimeManifestObject;
import io.pockethive.orchestrator.runtime.RuntimeOwnershipManifestFactory;
import io.pockethive.rabbit.api.RabbitConnectionSettings;
import io.pockethive.rabbit.api.RabbitResourceBeans;
import io.pockethive.rabbit.api.RabbitResourceNames;
import io.pockethive.rabbit.api.RabbitResources;
import io.pockethive.sink.clickhouse.ClickHouseSinkProperties;
import io.pockethive.swarm.model.NetworkMode;
import io.pockethive.swarm.model.RuntimeFilesystemContract;
import io.pockethive.swarm.model.SwarmStartupArtifactContract;
import io.pockethive.swarm.model.SwarmStartupArtifactReference;
import io.pockethive.swarm.model.lifecycle.RemoveError;
import io.pockethive.swarm.model.lifecycle.RemoveResource;
import io.pockethive.swarm.model.lifecycle.RemoveResourceType;
import io.pockethive.swarm.model.lifecycle.ResourcePlane;
import io.pockethive.topology.work.WorkTopologyChannels;
import io.pockethive.topology.work.WorkTopologyResolver;
import io.pockethive.work.config.WorkAdapterEnvironment;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Responsibility: adapt swarm container lifecycle operations to the configured runtime infrastructure.
 * Must not: resolve Rabbit connection fields or duplicate their container environment encoding.
 * Contract: RESP-ORCHESTRATOR-CONTAINER-LIFECYCLE — docs/architecture/runtime-responsibilities.md#resp-orchestrator-container-lifecycle.
 * Consumes RESP-RABBIT-CONNECTION for validated base settings and their shared export.
 * Existing compute, manifest and resource cleanup concerns remain CP-N05/C02 debt.
 */
@Service
public class ContainerLifecycleManager {
    private final WorkTopologyResolver workTopologyResolver;

    private static final Logger log = LoggerFactory.getLogger(ContainerLifecycleManager.class);
    private static final String SWARM_CONTROLLER_ROLE = "swarm-controller";
    private final RuntimeFilesystemMount runtimeFilesystemMount;
    private final ComputeHost docker;
    private final ComputeAdapter computeAdapter;
    private final SwarmStore store;
    private final RabbitResources amqp;
    private final OrchestratorProperties properties;
    private final ControlPlaneProperties controlPlaneProperties;
    private final WorkAdapterEnvironment workEnvironment;
    private final RabbitConnectionSettings rabbitConnection;
    private final JournalRunRegistration runMetadataWriter;
    private final ClickHouseSinkProperties clickHouseSink;
    private final RuntimeOwnershipManifestStore manifestStore;
    private final RuntimeOwnershipManifestFactory manifestFactory;
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
        ComputeHost docker,
        ComputeAdapter computeAdapter,
        SwarmStore store,
        @org.springframework.beans.factory.annotation.Qualifier(RabbitResourceBeans.CONTROL) RabbitResources amqp,
        OrchestratorProperties properties,
        ControlPlaneProperties controlPlaneProperties,
        RabbitConnectionSettings rabbitConnection,
        JournalRunRegistration runMetadataWriter,
        ClickHouseSinkProperties clickHouseSink,
        RuntimeOwnershipManifestStore manifestStore,
        RuntimeFilesystemMount runtimeFilesystemMount,
        WorkTopologyResolver workTopologyResolver,
        WorkAdapterEnvironment workEnvironment,
        RuntimeOwnershipManifestFactory manifestFactory) {
        this.workTopologyResolver = Objects.requireNonNull(workTopologyResolver, "workTopologyResolver");
        this.docker = Objects.requireNonNull(docker, "docker");
        this.computeAdapter = Objects.requireNonNull(computeAdapter, "computeAdapter");
        this.store = Objects.requireNonNull(store, "store");
        this.amqp = Objects.requireNonNull(amqp, "amqp");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.controlPlaneProperties = Objects.requireNonNull(controlPlaneProperties, "controlPlaneProperties");
        this.rabbitConnection = Objects.requireNonNull(rabbitConnection, "rabbitConnection");
        this.workEnvironment = Objects.requireNonNull(workEnvironment, "workEnvironment");
        this.runMetadataWriter = Objects.requireNonNull(runMetadataWriter, "runMetadataWriter");
        this.clickHouseSink = Objects.requireNonNull(clickHouseSink, "clickHouseSink");
        this.manifestStore = Objects.requireNonNull(manifestStore, "manifestStore");
        this.manifestFactory = Objects.requireNonNull(manifestFactory, "manifestFactory");
        this.runtimeFilesystemMount = Objects.requireNonNull(runtimeFilesystemMount, "runtimeFilesystemMount");
        this.resolvedAdapterType = requireConcreteAdapterType(computeAdapter.type());
    }

    public Swarm startSwarm(String swarmId,
                            String image,
                            String instanceId,
                            String runId,
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
        NetworkMode resolvedNetworkMode = Objects.requireNonNull(networkMode, "networkMode");
        String resolvedRunId = requireNonBlank(runId, "runId");
        MetricsSettings metrics = metricsSettings(properties.getMetrics());
        var workTopology = workTopologyResolver.resolve(resolvedSwarmId, WorkTopologyChannels.from(templateMetadata.bees()));
        var manifestResources = manifestFactory.resources(resolvedSwarmId, resolvedInstance, workTopology);
        ControllerSettings controllerSettings =
            new ControllerSettings(
                metrics,
                resolvedRunId);
        Map<String, String> env = new LinkedHashMap<>(
            ControlPlaneContainerEnvironmentFactory.controllerEnvironment(
                resolvedSwarmId,
                resolvedInstance,
                SWARM_CONTROLLER_ROLE,
                controlPlaneProperties,
                controllerSettings,
                rabbitConnection));
        env.putAll(workEnvironment.connectionEnvironment());
        env.putAll(workTopology.controllerEnvironment());
        applyClickHouseSinkEnv(env);
        env.put(
            RuntimeFilesystemContract.HOST_ROOT_ENV,
            runtimeFilesystemMount.hostRoot().toString());
        env.put(
            RuntimeFilesystemContract.LOCAL_ROOT_ENV,
            RuntimeFilesystemContract.CONTAINER_ROOT);
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
        resolvedAdapterType = requireConcreteAdapterType(computeAdapter.type());
        env.putAll(DockerControllerEnvironment.encode(dockerSocket, resolvedAdapterType));
        putEnvIfMissing(env, DockerControllerEnvironment.PLACEMENT_CONSTRAINTS_ENV, normalizeRuntimeRoot(swarmPlacementConstraints));
        env.put("POCKETHIVE_RUNTIME_IMAGE", resolvedImage);
        env.put("POCKETHIVE_TEMPLATE_ID", requireText(templateMetadata.templateId(), "templateId"));
        env.put(
            SwarmStartupArtifactContract.PATH_ENV,
            startupArtifact.path());
        env.put(
            SwarmStartupArtifactContract.SHA256_ENV,
            startupArtifact.sha256());
        env.put(DockerRuntimeNames.STACK_NAME_ENV, DockerRuntimeNames.stackName(resolvedSwarmId));
        putEnvIfMissing(env, "POCKETHIVE_SUT_ID", normalizeRuntimeRoot(sutId));
        env.put("POCKETHIVE_NETWORK_MODE", resolvedNetworkMode.name());
        putEnvIfMissing(env, "POCKETHIVE_NETWORK_PROFILE_ID", normalizeRuntimeRoot(networkProfileId));
        if (autoPullImages) {
            log.info("autoPullImages=true, pulling controller image {} before start", resolvedImage);
            docker.pullImage(resolvedImage);
        }
        env.put("POCKETHIVE_JOURNAL_RUN_ID", resolvedRunId);
        runMetadataWriter.upsertOnSwarmStart(resolvedSwarmId, resolvedRunId, templateMetadata);
        log.info("launching controller for swarm {} as instance {} using image {} (runId={})",
            resolvedSwarmId, resolvedInstance, resolvedImage, resolvedRunId);
        log.info("docker env: {}", redactEnv(env));
        java.util.List<String> volumes = new java.util.ArrayList<>();
        volumes.add(DockerControllerEnvironment.socketMount(dockerSocket));
        volumes.add(runtimeFilesystemMount.volume());
        ManagerSpec managerSpec = new ManagerSpec(
            resolvedInstance,
            resolvedImage,
            java.util.Map.copyOf(env),
            java.util.List.copyOf(volumes));
        String containerId = computeAdapter.startManager(managerSpec);
        log.info("controller container {} ({}) started for swarm {}", containerId, resolvedInstance, resolvedSwarmId);
        Swarm swarm = new Swarm(resolvedSwarmId, resolvedInstance, containerId, resolvedRunId, resolvedNetworkMode);
        if (templateMetadata != null) {
            swarm.attachTemplate(templateMetadata);
        }
        swarm.attachStartupArtifact(startupArtifact);
        store.register(swarm);
        manifestStore.save(manifestFactory.create(resolvedSwarmId, resolvedRunId, templateMetadata.templateId(),
            resolvedAdapterType, new RuntimeManifestObject(containerId,
                resolvedAdapterType == ComputeAdapterType.SWARM_STACK ? "service" : "container", "manager",
                SWARM_CONTROLLER_ROLE, resolvedInstance, resolvedImage), manifestResources));
        return swarm;
    }

    private static MetricsSettings metricsSettings(OrchestratorMetricsProperties metrics) {
        return new MetricsSettings(
            metrics.getAdapter(),
            metrics.getPublishInterval(),
            metrics.getClickHouse());
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
        var targets = new java.util.ArrayList<RemoveResource>();
        var failed = new java.util.ArrayList<RemoveResource>();
        var errors = new java.util.ArrayList<RemoveError>();

        var controller = new RemoveResource(
            RemoveResourceType.CONTROLLER_RUNTIME,
            swarm.getContainerId(), ResourcePlane.NONE);
        try {
            log.info("tearing down controller runtime {} for swarm {}", swarm.getContainerId(), swarmId);
            computeAdapter.stopManager(swarm.getContainerId());
            targets.add(controller);
        } catch (RuntimeException failure) {
            failed.add(controller);
            errors.add(removeError(failure, controller));
        }

        String basePrefix = controlPlaneProperties.getControlQueuePrefix();
        String controllerQueue = new SwarmControllerControlPlaneTopologyDescriptor(swarmId, basePrefix, new RabbitResourceNames())
            .controlQueue(swarm.getInstanceId())
            .map(ControlQueueDescriptor::name)
            .orElseThrow(() -> new IllegalStateException("Controller control queue is not defined"));
        var queue = new RemoveResource(
            RemoveResourceType.RABBIT_QUEUE,
            controllerQueue, ResourcePlane.CONTROL);
        try {
            log.info("deleting swarm-controller control queue {}", controllerQueue);
            amqp.deleteQueue(controllerQueue);
            targets.add(queue);
        } catch (RuntimeException failure) {
            failed.add(queue);
            errors.add(removeError(failure, queue));
        }
        return new ControllerRuntimeRemoval(targets, failed, errors);
    }

    private static RemoveError removeError(
        RuntimeException failure,
        RemoveResource resource) {
        return new RemoveError(
            failure.getClass().getSimpleName(),
            java.util.Objects.toString(failure.getMessage(), failure.getClass().getName()),
            resource);
    }

    public record ControllerRuntimeRemoval(
        java.util.List<RemoveResource> targetResources,
        java.util.List<RemoveResource> failedResources,
        java.util.List<RemoveError> errors) {
        public ControllerRuntimeRemoval {
            targetResources = java.util.List.copyOf(targetResources);
            failedResources = java.util.List.copyOf(failedResources);
            errors = java.util.List.copyOf(errors);
        }

        public boolean succeeded() {
            return failedResources.isEmpty() && errors.isEmpty();
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
