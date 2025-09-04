package io.pockethive.orchestrator.app;

import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmRegistry;
import io.pockethive.orchestrator.domain.SwarmStatus;
import io.pockethive.orchestrator.infra.docker.DockerContainerClient;
import org.springframework.stereotype.Service;

@Service
public class ContainerLifecycleManager {
    private final DockerContainerClient docker;
    private final SwarmRegistry registry;

    public ContainerLifecycleManager(DockerContainerClient docker, SwarmRegistry registry) {
        this.docker = docker;
        this.registry = registry;
    }

    public Swarm startSwarm(String image) {
        String containerId = docker.createAndStartContainer(image);
        Swarm swarm = new Swarm(containerId);
        swarm.setStatus(SwarmStatus.RUNNING);
        registry.register(swarm);
        return swarm;
    }

    public void stopSwarm(String swarmId) {
        registry.find(swarmId).ifPresent(swarm -> {
            docker.stopAndRemoveContainer(swarm.getContainerId());
            swarm.setStatus(SwarmStatus.STOPPED);
        });
    }
}
