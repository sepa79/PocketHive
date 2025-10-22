package io.pockethive.orchestrator.app;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.HostConfig;
import io.pockethive.controlplane.spring.ControlPlaneProperties;
import io.pockethive.docker.DockerContainerClient;
import io.pockethive.orchestrator.config.OrchestratorProperties;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmRegistry;
import io.pockethive.orchestrator.domain.SwarmStatus;
import java.time.Duration;
import java.util.Map;
import java.util.function.UnaryOperator;
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
    AmqpAdmin amqp;

    @Test
    void startSwarmCreatesAndRegisters() {
        SwarmRegistry registry = new SwarmRegistry();
        OrchestratorProperties properties = defaultProperties();
        ControlPlaneProperties controlPlane = controlPlaneProperties();
        when(docker.createAndStartContainer(eq("img"), anyMap(), anyString(), any()))
            .thenReturn("cid");
        ContainerLifecycleManager manager = new ContainerLifecycleManager(
            docker, registry, amqp, properties, controlPlane, rabbitProperties());

        Swarm swarm = manager.startSwarm("sw1", "img", "inst1");

        assertEquals("sw1", swarm.getId());
        assertEquals("inst1", swarm.getInstanceId());
        assertEquals("cid", swarm.getContainerId());
        assertEquals(SwarmStatus.CREATING, swarm.getStatus());
        assertTrue(registry.find("sw1").isPresent());
        ArgumentCaptor<Map<String, String>> envCaptor = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<String> nameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<UnaryOperator<HostConfig>> hostCaptor = ArgumentCaptor.forClass(UnaryOperator.class);
        verify(docker).createAndStartContainer(eq("img"), envCaptor.capture(), nameCaptor.capture(), hostCaptor.capture());
        assertEquals("inst1", nameCaptor.getValue());
        Map<String, String> env = envCaptor.getValue();
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
        assertEquals("/var/run/docker.sock", env.get("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_DOCKER_SOCKET_PATH"));
        assertEquals("/var/run/docker.sock", env.get("DOCKER_SOCKET_PATH"));
        assertEquals("unix:///var/run/docker.sock", env.get("DOCKER_HOST"));
        HostConfig customized = hostCaptor.getValue().apply(HostConfig.newHostConfig());
        Bind[] binds = customized.getBinds();
        assertNotNull(binds);
        assertEquals(1, binds.length);
        assertEquals("/var/run/docker.sock", binds[0].getPath());
        assertEquals("/var/run/docker.sock", binds[0].getVolume().getPath());
    }

    @Test
    void startSwarmUsesConfiguredDockerSocketPath() {
        SwarmRegistry registry = new SwarmRegistry();
        OrchestratorProperties properties = withDockerSocket("/custom/docker.sock");
        ControlPlaneProperties controlPlane = controlPlaneProperties();
        when(docker.createAndStartContainer(eq("img"), anyMap(), anyString(), any()))
            .thenReturn("cid");
        ContainerLifecycleManager manager = new ContainerLifecycleManager(
            docker, registry, amqp, properties, controlPlane, rabbitProperties());

        manager.startSwarm("sw1", "img", "inst1");

        ArgumentCaptor<Map<String, String>> envCaptor = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<UnaryOperator<HostConfig>> hostCaptor = ArgumentCaptor.forClass(UnaryOperator.class);
        verify(docker).createAndStartContainer(eq("img"), envCaptor.capture(), eq("inst1"), hostCaptor.capture());
        Map<String, String> env = envCaptor.getValue();
        assertEquals("/custom/docker.sock", env.get("DOCKER_SOCKET_PATH"));
        assertEquals("unix:///custom/docker.sock", env.get("DOCKER_HOST"));
        Bind[] binds = hostCaptor.getValue().apply(HostConfig.newHostConfig()).getBinds();
        assertNotNull(binds);
        assertEquals(1, binds.length);
        assertEquals("/custom/docker.sock", binds[0].getPath());
        assertEquals("/custom/docker.sock", binds[0].getVolume().getPath());
    }

    @Test
    void startSwarmPropagatesPushgatewaySettingsWhenConfigured() {
        SwarmRegistry registry = new SwarmRegistry();
        OrchestratorProperties properties = withPushgateway();
        ControlPlaneProperties controlPlane = controlPlaneProperties();
        when(docker.createAndStartContainer(eq("img"), anyMap(), anyString(), any()))
            .thenReturn("cid");
        ContainerLifecycleManager manager = new ContainerLifecycleManager(
            docker, registry, amqp, properties, controlPlane, rabbitProperties());

        manager.startSwarm("sw1", "img", "inst1");

        ArgumentCaptor<Map<String, String>> envCaptor = ArgumentCaptor.forClass(Map.class);
        verify(docker).createAndStartContainer(eq("img"), envCaptor.capture(), eq("inst1"), any());
        Map<String, String> env = envCaptor.getValue();
        assertEquals("http://push:9091", env.get("MANAGEMENT_PROMETHEUS_METRICS_EXPORT_PUSHGATEWAY_BASE_URL"));
        assertEquals("true", env.get("MANAGEMENT_PROMETHEUS_METRICS_EXPORT_PUSHGATEWAY_ENABLED"));
        assertEquals("PT15S", env.get("MANAGEMENT_PROMETHEUS_METRICS_EXPORT_PUSHGATEWAY_PUSH_RATE"));
        assertEquals("DELETE", env.get("MANAGEMENT_PROMETHEUS_METRICS_EXPORT_PUSHGATEWAY_SHUTDOWN_OPERATION"));
        assertEquals("sw1", env.get("MANAGEMENT_PROMETHEUS_METRICS_EXPORT_PUSHGATEWAY_JOB"));
    }

    @Test
    void stopSwarmMarksStoppedWithoutRemovingResources() {
        SwarmRegistry registry = new SwarmRegistry();
        Swarm swarm = new Swarm("sw1", "inst1", "cid");
        registry.register(swarm);
        registry.updateStatus(swarm.getId(), SwarmStatus.CREATING);
        registry.updateStatus(swarm.getId(), SwarmStatus.READY);
        registry.updateStatus(swarm.getId(), SwarmStatus.STARTING);
        registry.updateStatus(swarm.getId(), SwarmStatus.RUNNING);
        OrchestratorProperties properties = defaultProperties();
        ControlPlaneProperties controlPlane = controlPlaneProperties();
        ContainerLifecycleManager manager = new ContainerLifecycleManager(
            docker, registry, amqp, properties, controlPlane, rabbitProperties());

        manager.stopSwarm(swarm.getId());

        verifyNoInteractions(docker, amqp);
        assertEquals(SwarmStatus.STOPPED, swarm.getStatus());
    }

    @Test
    void stopSwarmIsIdempotent() {
        SwarmRegistry registry = new SwarmRegistry();
        Swarm swarm = new Swarm("sw1", "inst1", "cid");
        registry.register(swarm);
        registry.updateStatus(swarm.getId(), SwarmStatus.CREATING);
        registry.updateStatus(swarm.getId(), SwarmStatus.READY);
        registry.updateStatus(swarm.getId(), SwarmStatus.STARTING);
        registry.updateStatus(swarm.getId(), SwarmStatus.RUNNING);
        OrchestratorProperties properties = defaultProperties();
        ControlPlaneProperties controlPlane = controlPlaneProperties();
        ContainerLifecycleManager manager = new ContainerLifecycleManager(
            docker, registry, amqp, properties, controlPlane, rabbitProperties());

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
        Swarm swarm = new Swarm("sw1", "inst1", "cid");
        registry.register(swarm);
        registry.updateStatus(swarm.getId(), SwarmStatus.CREATING);
        registry.updateStatus(swarm.getId(), SwarmStatus.READY);
        registry.updateStatus(swarm.getId(), SwarmStatus.STARTING);
        registry.updateStatus(swarm.getId(), SwarmStatus.RUNNING);
        registry.updateStatus(swarm.getId(), SwarmStatus.FAILED);
        OrchestratorProperties properties = defaultProperties();
        ControlPlaneProperties controlPlane = controlPlaneProperties();
        ContainerLifecycleManager manager = new ContainerLifecycleManager(
            docker, registry, amqp, properties, controlPlane, rabbitProperties());

        assertDoesNotThrow(() -> manager.stopSwarm(swarm.getId()));
        assertEquals(SwarmStatus.STOPPED, swarm.getStatus());
    }

    @Test
    void removeSwarmTearsDownContainerAndQueues() {
        SwarmRegistry registry = new SwarmRegistry();
        Swarm swarm = new Swarm("sw1", "inst1", "cid");
        registry.register(swarm);
        OrchestratorProperties properties = defaultProperties();
        ControlPlaneProperties controlPlane = controlPlaneProperties();
        ContainerLifecycleManager manager = new ContainerLifecycleManager(
            docker, registry, amqp, properties, controlPlane, rabbitProperties());

        manager.removeSwarm(swarm.getId());

        verify(docker).stopAndRemoveContainer("cid");
        verify(amqp).deleteQueue("ph." + swarm.getId() + ".gen");
        verify(amqp).deleteQueue("ph." + swarm.getId() + ".mod");
        verify(amqp).deleteQueue("ph." + swarm.getId() + ".final");
        assertTrue(registry.find(swarm.getId()).isEmpty());
    }

    @Test
    void removeSwarmIsolatesQueuesPerSwarmId() {
        SwarmRegistry registry = new SwarmRegistry();
        Swarm sw1 = new Swarm("sw1", "inst1", "c1");
        Swarm sw2 = new Swarm("sw2", "inst2", "c2");
        registry.register(sw1);
        registry.register(sw2);
        OrchestratorProperties properties = defaultProperties();
        ControlPlaneProperties controlPlane = controlPlaneProperties();
        ContainerLifecycleManager manager = new ContainerLifecycleManager(
            docker, registry, amqp, properties, controlPlane, rabbitProperties());

        manager.removeSwarm(sw1.getId());

        verify(docker).stopAndRemoveContainer("c1");
        verify(amqp).deleteQueue("ph." + sw1.getId() + ".gen");
        verify(amqp).deleteQueue("ph." + sw1.getId() + ".mod");
        verify(amqp).deleteQueue("ph." + sw1.getId() + ".final");
        assertTrue(registry.find(sw1.getId()).isEmpty());
        assertTrue(registry.find(sw2.getId()).isPresent());
    }

    private static OrchestratorProperties defaultProperties() {
        return new OrchestratorProperties(
            new OrchestratorProperties.Orchestrator(
                "ph.control.orchestrator",
                "ph.control.orchestrator-status",
                new OrchestratorProperties.Rabbit(
                    "ph.logs",
                    new OrchestratorProperties.Logging(Boolean.FALSE)),
                new OrchestratorProperties.Pushgateway(Boolean.FALSE, null, Duration.ofMinutes(1), null),
                new OrchestratorProperties.Docker("/var/run/docker.sock"),
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
                new OrchestratorProperties.Pushgateway(Boolean.FALSE, null, Duration.ofMinutes(1), null),
                new OrchestratorProperties.Docker(socketPath),
                new OrchestratorProperties.ScenarioManager(
                    "http://scenario-manager:8080",
                    new OrchestratorProperties.Http(Duration.ofSeconds(5), Duration.ofSeconds(30)))));
    }

    private static OrchestratorProperties withPushgateway() {
        return new OrchestratorProperties(
            new OrchestratorProperties.Orchestrator(
                "ph.control.orchestrator",
                "ph.control.orchestrator-status",
                new OrchestratorProperties.Rabbit(
                    "ph.logs",
                    new OrchestratorProperties.Logging(Boolean.FALSE)),
                new OrchestratorProperties.Pushgateway(Boolean.TRUE, "http://push:9091", Duration.ofSeconds(15), "DELETE"),
                new OrchestratorProperties.Docker("/var/run/docker.sock"),
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
