package io.pockethive.orchestrator.app;

import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.HostConfig;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmRegistry;
import io.pockethive.orchestrator.domain.SwarmStatus;
import io.pockethive.docker.DockerContainerClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.AmqpAdmin;

import static com.github.stefanbirkner.systemlambda.SystemLambda.withEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ContainerLifecycleManagerTest {
    @Mock
    DockerContainerClient docker;
    @Mock
    AmqpAdmin amqp;

    @Test
    void startSwarmCreatesAndRegisters() {
        SwarmRegistry registry = new SwarmRegistry();
        when(docker.createAndStartContainer(eq("img"), anyMap(), anyString(), any())).thenReturn("cid");
        ContainerLifecycleManager manager = new ContainerLifecycleManager(docker, registry, amqp);

        Swarm swarm = manager.startSwarm("sw1", "img", "inst1");

        assertEquals("sw1", swarm.getId());
        assertEquals("inst1", swarm.getInstanceId());
        assertEquals("cid", swarm.getContainerId());
        assertEquals(SwarmStatus.CREATING, swarm.getStatus());
        assertTrue(registry.find("sw1").isPresent());
        ArgumentCaptor<java.util.Map<String, String>> envCaptor = ArgumentCaptor.forClass(java.util.Map.class);
        ArgumentCaptor<String> nameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<java.util.function.UnaryOperator<HostConfig>> hostCaptor = ArgumentCaptor.forClass(java.util.function.UnaryOperator.class);
        verify(docker).createAndStartContainer(eq("img"), envCaptor.capture(), nameCaptor.capture(), hostCaptor.capture());
        assertEquals("inst1", nameCaptor.getValue());
        assertEquals("-Dbee.name=inst1", envCaptor.getValue().get("JAVA_TOOL_OPTIONS"));
        assertEquals("/var/run/docker.sock", envCaptor.getValue().get("DOCKER_SOCKET_PATH"));
        assertEquals("unix:///var/run/docker.sock", envCaptor.getValue().get("DOCKER_HOST"));
        HostConfig hostConfig = HostConfig.newHostConfig();
        HostConfig customized = hostCaptor.getValue().apply(hostConfig);
        Bind[] binds = customized.getBinds();
        assertNotNull(binds);
        assertEquals(1, binds.length);
        assertEquals("/var/run/docker.sock", binds[0].getPath());
        assertEquals("/var/run/docker.sock", binds[0].getVolume().getPath());
    }

    @Test
    void startSwarmPropagatesCustomDockerSocketPath() {
        String previous = System.getProperty("DOCKER_SOCKET_PATH");
        System.setProperty("DOCKER_SOCKET_PATH", "/custom/docker.sock");
        try {
            SwarmRegistry registry = new SwarmRegistry();
            when(docker.createAndStartContainer(eq("img"), anyMap(), anyString(), any())).thenReturn("cid");
            ContainerLifecycleManager manager = new ContainerLifecycleManager(docker, registry, amqp);

            manager.startSwarm("sw1", "img", "inst1");

            ArgumentCaptor<java.util.Map<String, String>> envCaptor = ArgumentCaptor.forClass(java.util.Map.class);
            ArgumentCaptor<java.util.function.UnaryOperator<HostConfig>> hostCaptor = ArgumentCaptor.forClass(java.util.function.UnaryOperator.class);
            verify(docker).createAndStartContainer(eq("img"), envCaptor.capture(), eq("inst1"), hostCaptor.capture());
            java.util.Map<String, String> env = envCaptor.getValue();
            assertEquals("/custom/docker.sock", env.get("DOCKER_SOCKET_PATH"));
            assertEquals("unix:///custom/docker.sock", env.get("DOCKER_HOST"));
            HostConfig customized = hostCaptor.getValue().apply(HostConfig.newHostConfig());
            Bind[] binds = customized.getBinds();
            assertNotNull(binds);
            assertEquals(1, binds.length);
            assertEquals("/custom/docker.sock", binds[0].getPath());
            assertEquals("/custom/docker.sock", binds[0].getVolume().getPath());
        } finally {
            if (previous == null) {
                System.clearProperty("DOCKER_SOCKET_PATH");
            } else {
                System.setProperty("DOCKER_SOCKET_PATH", previous);
            }
        }
    }

    @Test
    void startSwarmPropagatesPushgatewaySettingsWhenConfigured() throws Exception {
        withEnvironmentVariable("MANAGEMENT_PROMETHEUS_METRICS_EXPORT_PUSHGATEWAY_BASE_URL", "http://push:9091")
            .and("MANAGEMENT_PROMETHEUS_METRICS_EXPORT_PUSHGATEWAY_ENABLED", "true")
            .and("MANAGEMENT_PROMETHEUS_METRICS_EXPORT_PUSHGATEWAY_PUSH_RATE", "15s")
            .and("MANAGEMENT_PROMETHEUS_METRICS_EXPORT_PUSHGATEWAY_SHUTDOWN_OPERATION", "DELETE")
            .execute(() -> {
                SwarmRegistry registry = new SwarmRegistry();
                when(docker.createAndStartContainer(eq("img"), anyMap(), anyString(), any())).thenReturn("cid");
                ContainerLifecycleManager manager = new ContainerLifecycleManager(docker, registry, amqp);

                manager.startSwarm("sw1", "img", "inst1");

                ArgumentCaptor<java.util.Map<String, String>> envCaptor = ArgumentCaptor.forClass(java.util.Map.class);
                verify(docker).createAndStartContainer(eq("img"), envCaptor.capture(), eq("inst1"), any());
                java.util.Map<String, String> env = envCaptor.getValue();
                assertEquals("http://push:9091", env.get("MANAGEMENT_PROMETHEUS_METRICS_EXPORT_PUSHGATEWAY_BASE_URL"));
                assertEquals("http://push:9091", env.get("PH_PUSHGATEWAY_BASE_URL"));
                assertEquals("true", env.get("MANAGEMENT_PROMETHEUS_METRICS_EXPORT_PUSHGATEWAY_ENABLED"));
                assertEquals("15s", env.get("MANAGEMENT_PROMETHEUS_METRICS_EXPORT_PUSHGATEWAY_PUSH_RATE"));
                assertEquals("DELETE", env.get("MANAGEMENT_PROMETHEUS_METRICS_EXPORT_PUSHGATEWAY_SHUTDOWN_OPERATION"));
                assertEquals("sw1", env.get("MANAGEMENT_PROMETHEUS_METRICS_EXPORT_PUSHGATEWAY_JOB"));
                assertEquals("inst1", env.get("MANAGEMENT_PROMETHEUS_METRICS_EXPORT_PUSHGATEWAY_GROUPING_KEY_INSTANCE"));
                assertNull(env.get("MANAGEMENT_METRICS_TAGS_SWARM"));
                assertNull(env.get("MANAGEMENT_METRICS_TAGS_INSTANCE"));
            });
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
        ContainerLifecycleManager manager = new ContainerLifecycleManager(docker, registry, amqp);

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
        ContainerLifecycleManager manager = new ContainerLifecycleManager(docker, registry, amqp);

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
        ContainerLifecycleManager manager = new ContainerLifecycleManager(docker, registry, amqp);

        assertDoesNotThrow(() -> manager.stopSwarm(swarm.getId()));
        assertEquals(SwarmStatus.STOPPED, swarm.getStatus());
    }

    @Test
    void removeSwarmTearsDownContainerAndQueues() {
        SwarmRegistry registry = new SwarmRegistry();
        Swarm swarm = new Swarm("sw1", "inst1", "cid");
        registry.register(swarm);
        ContainerLifecycleManager manager = new ContainerLifecycleManager(docker, registry, amqp);

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
        ContainerLifecycleManager manager = new ContainerLifecycleManager(docker, registry, amqp);

        manager.removeSwarm(sw1.getId());
        manager.removeSwarm(sw2.getId());

        verify(docker).stopAndRemoveContainer("c1");
        verify(docker).stopAndRemoveContainer("c2");
        verify(amqp).deleteQueue("ph.sw1.gen");
        verify(amqp).deleteQueue("ph.sw1.mod");
        verify(amqp).deleteQueue("ph.sw1.final");
        verify(amqp).deleteQueue("ph.sw2.gen");
        verify(amqp).deleteQueue("ph.sw2.mod");
        verify(amqp).deleteQueue("ph.sw2.final");
    }
}
