package io.pockethive.orchestrator.app;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.pockethive.controlplane.spring.ControlPlaneProperties;
import io.pockethive.docker.DockerContainerClient;
import io.pockethive.manager.ports.ComputeAdapter;
import io.pockethive.manager.runtime.ComputeAdapterType;
import io.pockethive.manager.runtime.ManagerSpec;
import io.pockethive.orchestrator.config.OrchestratorProperties;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmStore;
import io.pockethive.swarm.model.lifecycle.ControllerState;
import io.pockethive.orchestrator.domain.SwarmTemplateMetadata;
import io.pockethive.orchestrator.infra.JournalRunMetadataWriter;
import io.pockethive.orchestrator.runtime.RuntimeCleanupPorts.RuntimeOwnershipManifestStore;
import io.pockethive.orchestrator.runtime.RuntimeOwnershipManifest;
import io.pockethive.observability.metrics.PocketHiveMetricsAdapter;
import io.pockethive.sink.clickhouse.ClickHouseSinkProperties;
import io.pockethive.sink.clickhouse.metrics.ClickHouseMetricsSinkProperties;
import io.pockethive.swarm.model.NetworkMode;
import io.pockethive.swarm.model.Bee;
import io.pockethive.swarm.model.Work;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.boot.autoconfigure.amqp.RabbitProperties;

@ExtendWith(MockitoExtension.class)
class ContainerLifecycleManagerTest {

    @Mock
    DockerContainerClient docker;

    @Mock
    ComputeAdapter computeAdapter;

    @Mock
    AmqpAdmin amqp;

    @Mock
    JournalRunMetadataWriter runMetadataWriter;

    @BeforeEach
    void setUpComputeAdapterType() {
        lenient().when(computeAdapter.type()).thenReturn(ComputeAdapterType.DOCKER_SINGLE);
    }

    @Test
    void startSwarmCreatesAndRegisters() {
        SwarmStore registry = new SwarmStore();
        OrchestratorProperties properties = defaultProperties();
        ControlPlaneProperties controlPlane = controlPlaneProperties();
        when(computeAdapter.startManager(any(ManagerSpec.class))).thenReturn("cid");
        ContainerLifecycleManager manager = new ContainerLifecycleManager(
            docker, computeAdapter, registry, amqp, properties, controlPlane, rabbitProperties(), runMetadataWriter, new ClickHouseSinkProperties());

        Swarm swarm = manager.startSwarm(
            "sw1",
            "img",
            "inst1",
            startupMetadata("tpl-1", "img", List.of()),
            false,
            null,
            NetworkMode.DIRECT,
            null,
            startupArtifact());

        assertEquals("sw1", swarm.getId());
        assertEquals("inst1", swarm.getInstanceId());
        assertEquals("cid", swarm.getContainerId());
        assertEquals(ControllerState.PROVISIONING, swarm.getControllerState());
        assertTrue(registry.find("sw1").isPresent());
        ArgumentCaptor<ManagerSpec> specCaptor = ArgumentCaptor.forClass(ManagerSpec.class);
        verify(computeAdapter).startManager(specCaptor.capture());
        ManagerSpec spec = specCaptor.getValue();
        assertEquals("inst1", spec.id());
        assertEquals("img", spec.image());
        Map<String, String> env = spec.environment();
        assertEquals("inst1", env.get("POCKETHIVE_CONTROL_PLANE_INSTANCE_ID"));
        assertEquals("ph.control", env.get("POCKETHIVE_CONTROL_PLANE_EXCHANGE"));
        assertEquals("sw1", env.get("POCKETHIVE_CONTROL_PLANE_SWARM_ID"));
        assertEquals("tpl-1", env.get("POCKETHIVE_TEMPLATE_ID"));
        assertEquals("DIRECT", env.get("POCKETHIVE_NETWORK_MODE"));
        assertFalse(env.containsKey("RABBITMQ_HOST"));
        assertFalse(env.containsKey("RABBITMQ_PORT"));
        assertFalse(env.containsKey("RABBITMQ_DEFAULT_USER"));
        assertFalse(env.containsKey("RABBITMQ_DEFAULT_PASS"));
        assertFalse(env.containsKey("RABBITMQ_VHOST"));
        assertEquals("rabbitmq", env.get("SPRING_RABBITMQ_HOST"));
        assertEquals("5672", env.get("SPRING_RABBITMQ_PORT"));
        assertEquals("guest", env.get("SPRING_RABBITMQ_USERNAME"));
        assertEquals("guest", env.get("SPRING_RABBITMQ_PASSWORD"));
        assertEquals("/", env.get("SPRING_RABBITMQ_VIRTUAL_HOST"));
        assertEquals("false", env.get("POCKETHIVE_CONTROL_PLANE_WORKER_ENABLED"));
        assertEquals("swarm-controller", env.get("POCKETHIVE_CONTROL_PLANE_MANAGER_ROLE"));
        assertEquals(
            controlPlane.getControlQueuePrefix(),
            env.get("POCKETHIVE_CONTROL_PLANE_CONTROL_QUEUE_PREFIX"));
        assertEquals("ph.sw1", env.get("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_TRAFFIC_QUEUE_PREFIX"));
        assertEquals("ph.sw1.hive", env.get("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_TRAFFIC_HIVE_EXCHANGE"));
        assertFalse(env.containsKey("POCKETHIVE_LOGS_EXCHANGE"));
        assertFalse(env.containsKey("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_RABBIT_LOGS_EXCHANGE"));
        assertFalse(env.containsKey("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_RABBIT_LOGGING_ENABLED"));
        assertEquals("CLICKHOUSE", env.get("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_ADAPTER"));
        assertEquals("PT10S", env.get("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_PUBLISH_INTERVAL"));
        assertEquals(
            "http://clickhouse:8123",
            env.get("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_CLICKHOUSE_ENDPOINT"));
        assertEquals(
            ClickHouseMetricsSinkProperties.DEFAULT_TABLE,
            env.get("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_CLICKHOUSE_TABLE"));
        assertEquals("CLICKHOUSE", env.get("POCKETHIVE_METRICS_ADAPTER"));
        assertEquals("http://clickhouse:8123", env.get("POCKETHIVE_METRICS_CLICKHOUSE_ENDPOINT"));
        assertEquals("/var/run/docker.sock", env.get("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_DOCKER_SOCKET_PATH"));
        assertEquals("/var/run/docker.sock", env.get("DOCKER_SOCKET_PATH"));
        assertEquals("unix:///var/run/docker.sock", env.get("DOCKER_HOST"));
        List<String> volumes = spec.volumes();
        assertNotNull(volumes);
        assertEquals(1, volumes.size());
        assertEquals("/var/run/docker.sock:/var/run/docker.sock", volumes.get(0));
    }

    @Test
    void startSwarmWritesRuntimeOwnershipManifestForControllerAndRabbitTopology() {
        SwarmStore registry = new SwarmStore();
        OrchestratorProperties properties = defaultProperties();
        ControlPlaneProperties controlPlane = controlPlaneProperties();
        RecordingManifestStore manifests = new RecordingManifestStore();
        when(computeAdapter.startManager(any(ManagerSpec.class))).thenReturn("cid");
        ContainerLifecycleManager manager = new ContainerLifecycleManager(
            docker,
            computeAdapter,
            registry,
            amqp,
            properties,
            controlPlane,
            rabbitProperties(),
            runMetadataWriter,
            new ClickHouseSinkProperties(),
            manifests);

        manager.startSwarm(
            "sw1",
            "img",
            "inst1",
            startupMetadata(
                "tpl-1",
                "img",
                List.of(new Bee("processor", "processor:latest", Work.ofDefaults("gen", "final"), Map.of()))),
            false,
            null,
            NetworkMode.DIRECT,
            null,
            startupArtifact());

        RuntimeOwnershipManifest manifest = manifests.saved.getFirst();
        assertEquals("sw1", manifest.swarmId());
        assertEquals("tpl-1", manifest.templateId());
        assertEquals("DOCKER_SINGLE", manifest.computeAdapter());
        assertEquals("cid", manifest.runtimeObjects().getFirst().runtimeId());
        assertEquals("container", manifest.runtimeObjects().getFirst().runtimeType());
        assertEquals("manager", manifest.runtimeObjects().getFirst().resourceKind());
        assertEquals("swarm-controller", manifest.runtimeObjects().getFirst().role());
        assertEquals(List.of("ph.control.manager.sw1.swarm-controller.inst1"), manifest.rabbit().controlQueues());
        assertTrue(manifest.rabbit().workQueues().contains("ph.sw1.gen"));
        assertTrue(manifest.rabbit().workQueues().contains("ph.sw1.final"));
        assertEquals(List.of("ph.sw1.hive"), manifest.rabbit().exchanges());
    }

    @Test
    void startSwarmPropagatesNetworkMetadataToControllerEnv() {
        SwarmStore registry = new SwarmStore();
        OrchestratorProperties properties = defaultProperties();
        ControlPlaneProperties controlPlane = controlPlaneProperties();
        when(computeAdapter.startManager(any(ManagerSpec.class))).thenReturn("cid");
        ContainerLifecycleManager manager = new ContainerLifecycleManager(
            docker, computeAdapter, registry, amqp, properties, controlPlane, rabbitProperties(), runMetadataWriter, new ClickHouseSinkProperties());

        manager.startSwarm(
            "sw1",
            "img",
            "inst1",
            startupMetadata("tpl-1", "img", List.of()),
            false,
            "wiremock-proxy-local",
            NetworkMode.PROXIED,
            "passthrough",
            startupArtifact());

        ArgumentCaptor<ManagerSpec> specCaptor = ArgumentCaptor.forClass(ManagerSpec.class);
        verify(computeAdapter).startManager(specCaptor.capture());
        Map<String, String> env = specCaptor.getValue().environment();
        assertEquals("wiremock-proxy-local", env.get("POCKETHIVE_SUT_ID"));
        assertEquals("PROXIED", env.get("POCKETHIVE_NETWORK_MODE"));
        assertEquals("passthrough", env.get("POCKETHIVE_NETWORK_PROFILE_ID"));
    }

    @Test
    void startSwarmAppliesRepositoryPrefixWhenConfigured() {
        SwarmStore registry = new SwarmStore();
        OrchestratorProperties properties = withRepositoryPrefix("ghcr.io/acme/pockethive");
        ControlPlaneProperties controlPlane = controlPlaneProperties();
        when(computeAdapter.startManager(any(ManagerSpec.class))).thenReturn("cid");
        ContainerLifecycleManager manager = new ContainerLifecycleManager(
            docker, computeAdapter, registry, amqp, properties, controlPlane, rabbitProperties(), runMetadataWriter, new ClickHouseSinkProperties());

        Swarm swarm = manager.startSwarm(
            "sw1",
            "swarm-controller:latest",
            "inst1",
            startupMetadata("tpl-1", "swarm-controller:latest", List.of()),
            false,
            null,
            NetworkMode.DIRECT,
            null,
            startupArtifact());

        assertEquals("sw1", swarm.getId());
        ArgumentCaptor<ManagerSpec> specCaptor = ArgumentCaptor.forClass(ManagerSpec.class);
        verify(computeAdapter).startManager(specCaptor.capture());
        ManagerSpec spec = specCaptor.getValue();
        assertEquals("ghcr.io/acme/pockethive/swarm-controller:latest", spec.image());
        assertEquals("inst1", spec.id());
    }

    @Test
    void startSwarmUsesConfiguredDockerSocketPath() {
        SwarmStore registry = new SwarmStore();
        OrchestratorProperties properties = withDockerSocket("/custom/docker.sock");
        ControlPlaneProperties controlPlane = controlPlaneProperties();
        when(computeAdapter.startManager(any(ManagerSpec.class))).thenReturn("cid");
        ContainerLifecycleManager manager = new ContainerLifecycleManager(
            docker, computeAdapter, registry, amqp, properties, controlPlane, rabbitProperties(), runMetadataWriter, new ClickHouseSinkProperties());

        manager.startSwarm(
            "sw1",
            "img",
            "inst1",
            startupMetadata("tpl-1", "img", List.of()),
            false,
            null,
            NetworkMode.DIRECT,
            null,
            startupArtifact());

        ArgumentCaptor<ManagerSpec> specCaptor = ArgumentCaptor.forClass(ManagerSpec.class);
        verify(computeAdapter).startManager(specCaptor.capture());
        ManagerSpec spec = specCaptor.getValue();
        Map<String, String> env = spec.environment();
        assertEquals("/custom/docker.sock", env.get("DOCKER_SOCKET_PATH"));
        assertEquals("unix:///custom/docker.sock", env.get("DOCKER_HOST"));
        List<String> volumes = spec.volumes();
        assertNotNull(volumes);
        assertEquals(1, volumes.size());
        assertEquals("/custom/docker.sock:/custom/docker.sock", volumes.get(0));
    }

    @Test
    void removeControllerRuntimeReportsResourcesWithoutDeletingRegistryAuthority() {
        SwarmStore registry = new SwarmStore();
        Swarm swarm = new Swarm("sw1", "inst1", "cid", "run-1");
        swarm.attachTemplate(new SwarmTemplateMetadata(
            "tpl-1",
            "ctrl-image",
            List.of(new Bee("generator", "gen-image", Work.ofDefaults(null, "out"), Map.of()))));
        registry.register(swarm);
        OrchestratorProperties properties = defaultProperties();
        ControlPlaneProperties controlPlane = controlPlaneProperties();
        ContainerLifecycleManager manager = new ContainerLifecycleManager(
            docker, computeAdapter, registry, amqp, properties, controlPlane, rabbitProperties(), runMetadataWriter, new ClickHouseSinkProperties());

        var result = manager.removeControllerRuntime(swarm.getId());

        verify(computeAdapter).stopManager("cid");
        verify(amqp).deleteQueue("ph.control.manager.sw1.swarm-controller.inst1");
        assertTrue(result.succeeded());
        assertEquals(2, result.removedResources().size());
        assertTrue(registry.find(swarm.getId()).isPresent());
    }

    @Test
    void controllerRuntimeRemovalIsScopedToOneSwarm() {
        SwarmStore registry = new SwarmStore();
        Swarm sw1 = new Swarm("sw1", "inst1", "c1", "run-1");
        Swarm sw2 = new Swarm("sw2", "inst2", "c2", "run-2");
        registry.register(sw1);
        registry.register(sw2);
        OrchestratorProperties properties = defaultProperties();
        ControlPlaneProperties controlPlane = controlPlaneProperties();
        ContainerLifecycleManager manager = new ContainerLifecycleManager(
            docker, computeAdapter, registry, amqp, properties, controlPlane, rabbitProperties(), runMetadataWriter, new ClickHouseSinkProperties());

        manager.removeControllerRuntime(sw1.getId());

        verify(computeAdapter).stopManager("c1");
        verify(amqp).deleteQueue("ph.control.manager.sw1.swarm-controller.inst1");
        assertTrue(registry.find(sw1.getId()).isPresent());
        assertTrue(registry.find(sw2.getId()).isPresent());
    }

    @Test
    void preloadSwarmImagesPullsControllerAndBeeImages() {
        SwarmStore registry = new SwarmStore();
        Swarm swarm = new Swarm("sw1", "inst1", "cid", "run-1");
        swarm.attachTemplate(new SwarmTemplateMetadata(
            "tpl-1",
            "swarm-controller:latest",
            List.of(
                new Bee("generator", "generator:latest", Work.ofDefaults(null, "out"), Map.of()),
                new Bee("processor", "processor:latest", Work.ofDefaults("in", "out"), Map.of()))));
        registry.register(swarm);
        OrchestratorProperties properties = withRepositoryPrefix("ghcr.io/acme/pockethive");
        ControlPlaneProperties controlPlane = controlPlaneProperties();
        ContainerLifecycleManager manager = new ContainerLifecycleManager(
            docker, computeAdapter, registry, amqp, properties, controlPlane, rabbitProperties(), runMetadataWriter, new ClickHouseSinkProperties());

        manager.preloadSwarmImages("sw1");

        verify(docker).pullImage("ghcr.io/acme/pockethive/swarm-controller:latest");
        verify(docker).pullImage("ghcr.io/acme/pockethive/generator:latest");
        verify(docker).pullImage("ghcr.io/acme/pockethive/processor:latest");
    }

    private static OrchestratorProperties defaultProperties() {
        return new OrchestratorProperties(
            new OrchestratorProperties.Orchestrator(
                "ph.control.orchestrator",
                "ph.control.orchestrator-status",
                defaultMetrics(),
                new OrchestratorProperties.Docker("/var/run/docker.sock", null),
                new OrchestratorProperties.Images(null),
                new OrchestratorProperties.ScenarioManager(
                    "http://scenario-manager:8080",
                    new OrchestratorProperties.Http(Duration.ofSeconds(5), Duration.ofSeconds(30))),
                new OrchestratorProperties.NetworkProxyManager(
                    "http://network-proxy-manager:8080",
                    new OrchestratorProperties.Http(Duration.ofSeconds(5), Duration.ofSeconds(30)))));
    }

    private static OrchestratorProperties withDockerSocket(String socketPath) {
        return new OrchestratorProperties(
            new OrchestratorProperties.Orchestrator(
                "ph.control.orchestrator",
                "ph.control.orchestrator-status",
                defaultMetrics(),
                new OrchestratorProperties.Docker(socketPath, null),
                new OrchestratorProperties.Images(null),
                new OrchestratorProperties.ScenarioManager(
                    "http://scenario-manager:8080",
                    new OrchestratorProperties.Http(Duration.ofSeconds(5), Duration.ofSeconds(30))),
                new OrchestratorProperties.NetworkProxyManager(
                    "http://network-proxy-manager:8080",
                    new OrchestratorProperties.Http(Duration.ofSeconds(5), Duration.ofSeconds(30)))));
    }

    private static OrchestratorProperties withRepositoryPrefix(String prefix) {
        return new OrchestratorProperties(
            new OrchestratorProperties.Orchestrator(
                "ph.control.orchestrator",
                "ph.control.orchestrator-status",
                defaultMetrics(),
                new OrchestratorProperties.Docker("/var/run/docker.sock", null),
                new OrchestratorProperties.Images(prefix),
                new OrchestratorProperties.ScenarioManager(
                    "http://scenario-manager:8080",
                    new OrchestratorProperties.Http(Duration.ofSeconds(5), Duration.ofSeconds(30))),
                new OrchestratorProperties.NetworkProxyManager(
                    "http://network-proxy-manager:8080",
                    new OrchestratorProperties.Http(Duration.ofSeconds(5), Duration.ofSeconds(30)))));
    }

    private static ControlPlaneProperties controlPlaneProperties() {
        ControlPlaneProperties properties = new ControlPlaneProperties();
        properties.setExchange("ph.control");
        properties.setControlQueuePrefix("ph.control.manager");
        properties.setSwarmId("orchestrator-swarm");
        properties.setInstanceId("orch-instance");
        properties.getManager().setRole("orchestrator");
        properties.getWorker().setEnabled(false);
        return properties;
    }

    private static OrchestratorProperties.Metrics defaultMetrics() {
        return new OrchestratorProperties.Metrics(
            PocketHiveMetricsAdapter.CLICKHOUSE,
            Duration.ofSeconds(10),
            clickHouseMetrics());
    }

    private static ClickHouseMetricsSinkProperties clickHouseMetrics() {
        ClickHouseMetricsSinkProperties properties = new ClickHouseMetricsSinkProperties();
        properties.setEndpoint("http://clickhouse:8123");
        return properties;
    }

    private static RabbitProperties rabbitProperties() {
        RabbitProperties properties = new RabbitProperties();
        properties.setHost("rabbitmq");
        properties.setPort(5672);
        properties.setUsername("guest");
        properties.setPassword("guest");
        properties.setVirtualHost("/");
        return properties;
    }

    private static SwarmTemplateMetadata startupMetadata(String templateId,
                                                         String controllerImage,
                                                         List<Bee> bees) {
        return new SwarmTemplateMetadata(
            templateId,
            controllerImage,
            bees);
    }

    private static io.pockethive.swarm.model.SwarmStartupArtifactReference startupArtifact() {
        return new io.pockethive.swarm.model.SwarmStartupArtifactReference(
            "/app/scenarios-runtime/sw1/runtime-artifacts/startup.json",
            "a".repeat(64));
    }

    private static final class RecordingManifestStore implements RuntimeOwnershipManifestStore {
        private final List<RuntimeOwnershipManifest> saved = new ArrayList<>();

        @Override
        public void save(RuntimeOwnershipManifest manifest) {
            saved.add(manifest);
        }

        @Override
        public Optional<RuntimeOwnershipManifest> find(String swarmId, String runId) {
            return saved.stream()
                .filter(manifest -> manifest.swarmId().equals(swarmId) && manifest.runId().equals(runId))
                .findFirst();
        }

        @Override
        public Optional<RuntimeOwnershipManifest> findLatest(String swarmId) {
            return saved.stream()
                .filter(manifest -> manifest.swarmId().equals(swarmId))
                .findFirst();
        }
    }
}
