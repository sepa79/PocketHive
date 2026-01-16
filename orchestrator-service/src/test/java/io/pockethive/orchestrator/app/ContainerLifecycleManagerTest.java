package io.pockethive.orchestrator.app;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.pockethive.controlplane.spring.ControlPlaneProperties;
import io.pockethive.docker.DockerContainerClient;
import io.pockethive.manager.ports.ComputeAdapter;
import io.pockethive.manager.runtime.ManagerSpec;
import io.pockethive.orchestrator.config.OrchestratorProperties;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmRegistry;
import io.pockethive.orchestrator.domain.SwarmStatus;
import io.pockethive.orchestrator.domain.SwarmTemplateMetadata;
import io.pockethive.orchestrator.infra.JournalRunMetadataWriter;
import io.pockethive.swarm.model.Bee;
import io.pockethive.swarm.model.Work;
import java.time.Duration;
import java.util.List;
import java.util.Map;
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

    @Test
    void startSwarmCreatesAndRegisters() {
        SwarmRegistry registry = new SwarmRegistry();
        OrchestratorProperties properties = defaultProperties();
        ControlPlaneProperties controlPlane = controlPlaneProperties();
        when(computeAdapter.startManager(any(ManagerSpec.class))).thenReturn("cid");
        ContainerLifecycleManager manager = new ContainerLifecycleManager(
            docker, computeAdapter, registry, amqp, properties, controlPlane, rabbitProperties(), runMetadataWriter);

        Swarm swarm = manager.startSwarm("sw1", "img", "inst1");

        assertEquals("sw1", swarm.getId());
        assertEquals("inst1", swarm.getInstanceId());
        assertEquals("cid", swarm.getContainerId());
        assertEquals(SwarmStatus.CREATING, swarm.getStatus());
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
        assertEquals("ph.logs", env.get("POCKETHIVE_LOGS_EXCHANGE"));
        assertEquals("false", env.get("POCKETHIVE_CONTROL_PLANE_WORKER_ENABLED"));
        assertEquals("swarm-controller", env.get("POCKETHIVE_CONTROL_PLANE_MANAGER_ROLE"));
        assertEquals(
            controlPlane.getControlQueuePrefix(),
            env.get("POCKETHIVE_CONTROL_PLANE_CONTROL_QUEUE_PREFIX"));
        assertEquals("ph.sw1", env.get("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_TRAFFIC_QUEUE_PREFIX"));
        assertEquals("ph.sw1.hive", env.get("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_TRAFFIC_HIVE_EXCHANGE"));
        assertEquals("ph.logs", env.get("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_RABBIT_LOGS_EXCHANGE"));
        assertEquals("false", env.get("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_RABBIT_LOGGING_ENABLED"));
        assertEquals("true", env.get("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_PUSHGATEWAY_ENABLED"));
        assertEquals("http://pushgateway:9091", env.get("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_PUSHGATEWAY_BASE_URL"));
        assertEquals("PT1M", env.get("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_PUSHGATEWAY_PUSH_RATE"));
        assertEquals("DELETE", env.get("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_PUSHGATEWAY_SHUTDOWN_OPERATION"));
        assertEquals("/var/run/docker.sock", env.get("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_DOCKER_SOCKET_PATH"));
        assertEquals("/var/run/docker.sock", env.get("DOCKER_SOCKET_PATH"));
        assertEquals("unix:///var/run/docker.sock", env.get("DOCKER_HOST"));
        List<String> volumes = spec.volumes();
        assertNotNull(volumes);
        assertEquals(1, volumes.size());
        assertEquals("/var/run/docker.sock:/var/run/docker.sock", volumes.get(0));
    }

    @Test
    void startSwarmAppliesRepositoryPrefixWhenConfigured() {
        SwarmRegistry registry = new SwarmRegistry();
        OrchestratorProperties properties = withRepositoryPrefix("ghcr.io/acme/pockethive");
        ControlPlaneProperties controlPlane = controlPlaneProperties();
        when(computeAdapter.startManager(any(ManagerSpec.class))).thenReturn("cid");
        ContainerLifecycleManager manager = new ContainerLifecycleManager(
            docker, computeAdapter, registry, amqp, properties, controlPlane, rabbitProperties(), runMetadataWriter);

        Swarm swarm = manager.startSwarm("sw1", "swarm-controller:latest", "inst1");

        assertEquals("sw1", swarm.getId());
        ArgumentCaptor<ManagerSpec> specCaptor = ArgumentCaptor.forClass(ManagerSpec.class);
        verify(computeAdapter).startManager(specCaptor.capture());
        ManagerSpec spec = specCaptor.getValue();
        assertEquals("ghcr.io/acme/pockethive/swarm-controller:latest", spec.image());
        assertEquals("inst1", spec.id());
    }

    @Test
    void startSwarmUsesConfiguredDockerSocketPath() {
        SwarmRegistry registry = new SwarmRegistry();
        OrchestratorProperties properties = withDockerSocket("/custom/docker.sock");
        ControlPlaneProperties controlPlane = controlPlaneProperties();
        when(computeAdapter.startManager(any(ManagerSpec.class))).thenReturn("cid");
        ContainerLifecycleManager manager = new ContainerLifecycleManager(
            docker, computeAdapter, registry, amqp, properties, controlPlane, rabbitProperties(), runMetadataWriter);

        manager.startSwarm("sw1", "img", "inst1");

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
    void stopSwarmMarksStoppedWithoutRemovingResources() {
        SwarmRegistry registry = new SwarmRegistry();
        Swarm swarm = new Swarm("sw1", "inst1", "cid", "run-1");
        registry.register(swarm);
        registry.updateStatus(swarm.getId(), SwarmStatus.CREATING);
        registry.updateStatus(swarm.getId(), SwarmStatus.READY);
        registry.updateStatus(swarm.getId(), SwarmStatus.STARTING);
        registry.updateStatus(swarm.getId(), SwarmStatus.RUNNING);
        OrchestratorProperties properties = defaultProperties();
        ControlPlaneProperties controlPlane = controlPlaneProperties();
        ContainerLifecycleManager manager = new ContainerLifecycleManager(
            docker, computeAdapter, registry, amqp, properties, controlPlane, rabbitProperties(), runMetadataWriter);

        manager.stopSwarm(swarm.getId());

        verifyNoInteractions(docker, amqp);
        assertEquals(SwarmStatus.STOPPED, swarm.getStatus());
    }

    @Test
    void stopSwarmIsIdempotent() {
        SwarmRegistry registry = new SwarmRegistry();
        Swarm swarm = new Swarm("sw1", "inst1", "cid", "run-1");
        registry.register(swarm);
        registry.updateStatus(swarm.getId(), SwarmStatus.CREATING);
        registry.updateStatus(swarm.getId(), SwarmStatus.READY);
        registry.updateStatus(swarm.getId(), SwarmStatus.STARTING);
        registry.updateStatus(swarm.getId(), SwarmStatus.RUNNING);
        OrchestratorProperties properties = defaultProperties();
        ControlPlaneProperties controlPlane = controlPlaneProperties();
        ContainerLifecycleManager manager = new ContainerLifecycleManager(
            docker, computeAdapter, registry, amqp, properties, controlPlane, rabbitProperties(), runMetadataWriter);

        assertDoesNotThrow(() -> {
            manager.stopSwarm(swarm.getId());
            manager.stopSwarm(swarm.getId());
        });
        assertEquals(SwarmStatus.STOPPED, swarm.getStatus());
        verifyNoInteractions(docker, amqp);
    }

    @Test
    void stopSwarmRecoversAfterFailure() {
        SwarmRegistry registry = new SwarmRegistry();
        Swarm swarm = new Swarm("sw1", "inst1", "cid", "run-1");
        registry.register(swarm);
        registry.updateStatus(swarm.getId(), SwarmStatus.CREATING);
        registry.updateStatus(swarm.getId(), SwarmStatus.READY);
        registry.updateStatus(swarm.getId(), SwarmStatus.STARTING);
        registry.updateStatus(swarm.getId(), SwarmStatus.RUNNING);
        registry.updateStatus(swarm.getId(), SwarmStatus.FAILED);
        OrchestratorProperties properties = defaultProperties();
        ControlPlaneProperties controlPlane = controlPlaneProperties();
        ContainerLifecycleManager manager = new ContainerLifecycleManager(
            docker, computeAdapter, registry, amqp, properties, controlPlane, rabbitProperties(), runMetadataWriter);

        assertDoesNotThrow(() -> manager.stopSwarm(swarm.getId()));
        assertEquals(SwarmStatus.STOPPED, swarm.getStatus());
    }

    @Test
    void removeSwarmTearsDownContainerAndQueues() {
        SwarmRegistry registry = new SwarmRegistry();
        Swarm swarm = new Swarm("sw1", "inst1", "cid", "run-1");
        swarm.attachTemplate(new SwarmTemplateMetadata(
            "tpl-1",
            "ctrl-image",
            List.of(new Bee("generator", "gen-image", Work.ofDefaults(null, "out"), Map.of()))));
        registry.register(swarm);
        OrchestratorProperties properties = defaultProperties();
        ControlPlaneProperties controlPlane = controlPlaneProperties();
        ContainerLifecycleManager manager = new ContainerLifecycleManager(
            docker, computeAdapter, registry, amqp, properties, controlPlane, rabbitProperties(), runMetadataWriter);

        manager.removeSwarm(swarm.getId());

        verify(computeAdapter).stopManager("cid");
        verify(amqp).deleteQueue("ph." + swarm.getId() + ".gen");
        verify(amqp).deleteQueue("ph." + swarm.getId() + ".mod");
        verify(amqp).deleteQueue("ph." + swarm.getId() + ".final");
        assertTrue(registry.find(swarm.getId()).isEmpty());
        assertTrue(swarm.templateMetadata().isEmpty());
    }

    @Test
    void removeSwarmIsolatesQueuesPerSwarmId() {
        SwarmRegistry registry = new SwarmRegistry();
        Swarm sw1 = new Swarm("sw1", "inst1", "c1", "run-1");
        Swarm sw2 = new Swarm("sw2", "inst2", "c2", "run-2");
        registry.register(sw1);
        registry.register(sw2);
        OrchestratorProperties properties = defaultProperties();
        ControlPlaneProperties controlPlane = controlPlaneProperties();
        ContainerLifecycleManager manager = new ContainerLifecycleManager(
            docker, computeAdapter, registry, amqp, properties, controlPlane, rabbitProperties(), runMetadataWriter);

        manager.removeSwarm(sw1.getId());

        verify(computeAdapter).stopManager("c1");
        verify(amqp).deleteQueue("ph." + sw1.getId() + ".gen");
        verify(amqp).deleteQueue("ph." + sw1.getId() + ".mod");
        verify(amqp).deleteQueue("ph." + sw1.getId() + ".final");
        assertTrue(registry.find(sw1.getId()).isEmpty());
        assertTrue(registry.find(sw2.getId()).isPresent());
    }

    @Test
    void preloadSwarmImagesPullsControllerAndBeeImages() {
        SwarmRegistry registry = new SwarmRegistry();
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
            docker, computeAdapter, registry, amqp, properties, controlPlane, rabbitProperties(), runMetadataWriter);

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
                new OrchestratorProperties.Rabbit(
                    "ph.logs",
                    new OrchestratorProperties.Logging(Boolean.FALSE)),
                new OrchestratorProperties.Metrics(
                    new OrchestratorProperties.Pushgateway(
                        true,
                        "http://pushgateway:9091",
                        Duration.ofMinutes(1),
                        "DELETE",
                        "swarm-job",
                        new OrchestratorProperties.GroupingKey("controller-instance"))),
                new OrchestratorProperties.Docker("/var/run/docker.sock", null),
                new OrchestratorProperties.Images(null),
                new OrchestratorProperties.ScenarioManager(
                    "http://scenario-manager:8080",
                    new OrchestratorProperties.Http(Duration.ofSeconds(5), Duration.ofSeconds(30)))));
    }

    private static OrchestratorProperties withDockerSocket(String socketPath) {
        return new OrchestratorProperties(
            new OrchestratorProperties.Orchestrator(
                "ph.control.orchestrator",
                "ph.control.orchestrator-status",
                new OrchestratorProperties.Rabbit(
                    "ph.logs",
                    new OrchestratorProperties.Logging(Boolean.FALSE)),
                new OrchestratorProperties.Metrics(
                    new OrchestratorProperties.Pushgateway(
                        true,
                        "http://pushgateway:9091",
                        Duration.ofMinutes(1),
                        "DELETE",
                        "swarm-job",
                        new OrchestratorProperties.GroupingKey("controller-instance"))),
                new OrchestratorProperties.Docker(socketPath, null),
                new OrchestratorProperties.Images(null),
                new OrchestratorProperties.ScenarioManager(
                    "http://scenario-manager:8080",
                    new OrchestratorProperties.Http(Duration.ofSeconds(5), Duration.ofSeconds(30)))));
    }

    private static OrchestratorProperties withRepositoryPrefix(String prefix) {
        return new OrchestratorProperties(
            new OrchestratorProperties.Orchestrator(
                "ph.control.orchestrator",
                "ph.control.orchestrator-status",
                new OrchestratorProperties.Rabbit(
                    "ph.logs",
                    new OrchestratorProperties.Logging(Boolean.FALSE)),
                new OrchestratorProperties.Metrics(
                    new OrchestratorProperties.Pushgateway(
                        true,
                        "http://pushgateway:9091",
                        Duration.ofMinutes(1),
                        "DELETE",
                        "swarm-job",
                        new OrchestratorProperties.GroupingKey("controller-instance"))),
                new OrchestratorProperties.Docker("/var/run/docker.sock", null),
                new OrchestratorProperties.Images(prefix),
                new OrchestratorProperties.ScenarioManager(
                    "http://scenario-manager:8080",
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

    private static RabbitProperties rabbitProperties() {
        RabbitProperties properties = new RabbitProperties();
        properties.setHost("rabbitmq");
        properties.setPort(5672);
        properties.setUsername("guest");
        properties.setPassword("guest");
        properties.setVirtualHost("/");
        return properties;
    }
}
