package io.pockethive.orchestrator.app;

import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmRegistry;
import io.pockethive.orchestrator.domain.SwarmStatus;
import io.pockethive.orchestrator.domain.SwarmTemplate;
import io.pockethive.orchestrator.infra.docker.DockerContainerClient;
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
        when(docker.createAndStartContainer("img")).thenReturn("cid");
        ContainerLifecycleManager manager = new ContainerLifecycleManager(docker, registry, template, amqp);

        Swarm swarm = manager.startSwarm("sw1");

        assertEquals("sw1", swarm.getId());
        assertEquals("cid", swarm.getContainerId());
        assertEquals(SwarmStatus.RUNNING, swarm.getStatus());
        assertTrue(registry.find("sw1").isPresent());
        verify(docker).createAndStartContainer("img");
    }

    @Test
    void stopSwarmStopsContainer() {
        SwarmRegistry registry = new SwarmRegistry();
        Swarm swarm = new Swarm("cid");
        registry.register(swarm);
        SwarmTemplate template = new SwarmTemplate();
        ContainerLifecycleManager manager = new ContainerLifecycleManager(docker, registry, template, amqp);

        manager.stopSwarm(swarm.getId());

        verify(docker).stopAndRemoveContainer("cid");
        verify(amqp).deleteQueue("ph.gen." + swarm.getId());
        verify(amqp).deleteQueue("ph.mod." + swarm.getId());
        verify(amqp).deleteQueue("ph.final." + swarm.getId());
        assertEquals(SwarmStatus.STOPPED, swarm.getStatus());
    }

    @Test
    void stopSwarmIsolatesQueuesPerSwarmId() {
        SwarmRegistry registry = new SwarmRegistry();
        Swarm sw1 = new Swarm("sw1", "c1");
        Swarm sw2 = new Swarm("sw2", "c2");
        registry.register(sw1);
        registry.register(sw2);
        SwarmTemplate template = new SwarmTemplate();
        ContainerLifecycleManager manager = new ContainerLifecycleManager(docker, registry, template, amqp);

        manager.stopSwarm(sw1.getId());
        manager.stopSwarm(sw2.getId());

        verify(docker).stopAndRemoveContainer("c1");
        verify(docker).stopAndRemoveContainer("c2");
        verify(amqp).deleteQueue("ph.gen.sw1");
        verify(amqp).deleteQueue("ph.mod.sw1");
        verify(amqp).deleteQueue("ph.final.sw1");
        verify(amqp).deleteQueue("ph.gen.sw2");
        verify(amqp).deleteQueue("ph.mod.sw2");
        verify(amqp).deleteQueue("ph.final.sw2");
    }
}
