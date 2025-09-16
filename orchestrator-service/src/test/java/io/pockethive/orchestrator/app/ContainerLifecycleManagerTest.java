package io.pockethive.orchestrator.app;

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

import static org.junit.jupiter.api.Assertions.*;
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
        when(docker.createAndStartContainer(eq("img"), anyMap(), anyString())).thenReturn("cid");
        ContainerLifecycleManager manager = new ContainerLifecycleManager(docker, registry, amqp);

        Swarm swarm = manager.startSwarm("sw1", "img", "inst1");

        assertEquals("sw1", swarm.getId());
        assertEquals("inst1", swarm.getInstanceId());
        assertEquals("cid", swarm.getContainerId());
        assertEquals(SwarmStatus.CREATING, swarm.getStatus());
        assertTrue(registry.find("sw1").isPresent());
        ArgumentCaptor<java.util.Map<String, String>> envCaptor = ArgumentCaptor.forClass(java.util.Map.class);
        ArgumentCaptor<String> nameCaptor = ArgumentCaptor.forClass(String.class);
        verify(docker).createAndStartContainer(eq("img"), envCaptor.capture(), nameCaptor.capture());
        assertEquals("inst1", nameCaptor.getValue());
        assertEquals("-Dbee.name=inst1", envCaptor.getValue().get("JAVA_TOOL_OPTIONS"));
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
