package io.pockethive.orchestrator.app;

import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmRegistry;
import io.pockethive.orchestrator.domain.SwarmStatus;
import io.pockethive.orchestrator.infra.docker.DockerContainerClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ContainerLifecycleManagerTest {
    @Mock
    DockerContainerClient docker;

    @Test
    void startSwarmCreatesAndRegisters() {
        SwarmRegistry registry = new SwarmRegistry();
        when(docker.createAndStartContainer("img")).thenReturn("cid");
        ContainerLifecycleManager manager = new ContainerLifecycleManager(docker, registry);

        Swarm swarm = manager.startSwarm("img");

        assertEquals("cid", swarm.getContainerId());
        assertEquals(SwarmStatus.RUNNING, swarm.getStatus());
        assertTrue(registry.find(swarm.getId()).isPresent());
        verify(docker).createAndStartContainer("img");
    }

    @Test
    void stopSwarmStopsContainer() {
        SwarmRegistry registry = new SwarmRegistry();
        Swarm swarm = new Swarm("cid");
        registry.register(swarm);
        ContainerLifecycleManager manager = new ContainerLifecycleManager(docker, registry);

        manager.stopSwarm(swarm.getId());

        verify(docker).stopAndRemoveContainer("cid");
        assertEquals(SwarmStatus.STOPPED, swarm.getStatus());
    }
}
