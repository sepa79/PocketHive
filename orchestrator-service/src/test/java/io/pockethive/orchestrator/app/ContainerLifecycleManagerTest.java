package io.pockethive.orchestrator.app;

import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmRegistry;
import io.pockethive.orchestrator.domain.SwarmStatus;
import io.pockethive.orchestrator.domain.SwarmTemplate;
import io.pockethive.docker.DockerContainerClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
        SwarmTemplate template = new SwarmTemplate();
        template.setImage("img");
        when(docker.createAndStartContainer(eq("img"), anyMap())).thenReturn("cid");
        ContainerLifecycleManager manager = new ContainerLifecycleManager(docker, registry, template, amqp);

        Swarm swarm = manager.startSwarm("sw1", "inst1");

        assertEquals("sw1", swarm.getId());
        assertEquals("inst1", swarm.getInstanceId());
        assertEquals("cid", swarm.getContainerId());
        assertEquals(SwarmStatus.RUNNING, swarm.getStatus());
        assertTrue(registry.find("sw1").isPresent());
        verify(docker).createAndStartContainer(eq("img"), anyMap());
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
        SwarmTemplate template = new SwarmTemplate();
        ContainerLifecycleManager manager = new ContainerLifecycleManager(docker, registry, template, amqp);

        manager.stopSwarm(swarm.getId());

        verifyNoInteractions(docker, amqp);
        assertEquals(SwarmStatus.STOPPED, swarm.getStatus());
    }

    @Test
    void removeSwarmTearsDownContainerAndQueues() {
        SwarmRegistry registry = new SwarmRegistry();
        Swarm swarm = new Swarm("sw1", "inst1", "cid");
        registry.register(swarm);
        SwarmTemplate template = new SwarmTemplate();
        ContainerLifecycleManager manager = new ContainerLifecycleManager(docker, registry, template, amqp);

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
        SwarmTemplate template = new SwarmTemplate();
        ContainerLifecycleManager manager = new ContainerLifecycleManager(docker, registry, template, amqp);

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
