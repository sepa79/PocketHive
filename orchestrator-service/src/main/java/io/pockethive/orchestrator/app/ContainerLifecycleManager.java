package io.pockethive.orchestrator.app;

import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmRegistry;
import io.pockethive.orchestrator.domain.SwarmStatus;
import io.pockethive.orchestrator.domain.SwarmTemplate;
import io.pockethive.orchestrator.infra.docker.DockerContainerClient;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.stereotype.Service;

@Service
public class ContainerLifecycleManager {
    private final DockerContainerClient docker;
    private final SwarmRegistry registry;
    private final SwarmTemplate template;
    private final AmqpAdmin amqp;

    public ContainerLifecycleManager(DockerContainerClient docker, SwarmRegistry registry, SwarmTemplate template, AmqpAdmin amqp) {
        this.docker = docker;
        this.registry = registry;
        this.template = template;
        this.amqp = amqp;
    }

    public Swarm startSwarm(String swarmId, String instanceId) {
        return startSwarm(swarmId, template.getImage(), instanceId);
    }

    public Swarm startSwarm(String swarmId, String image, String instanceId) {
        java.util.Map<String, String> env = java.util.Map.of("JAVA_TOOL_OPTIONS", "-Dbee.name=" + instanceId);
        String containerId = docker.createAndStartContainer(image, env);
        Swarm swarm = new Swarm(swarmId, instanceId, containerId);
        swarm.setStatus(SwarmStatus.RUNNING);
        registry.register(swarm);
        return swarm;
    }

    public void stopSwarm(String swarmId) {
        registry.find(swarmId).ifPresent(swarm -> {
            docker.stopAndRemoveContainer(swarm.getContainerId());
            amqp.deleteQueue("ph." + swarmId + ".gen");
            amqp.deleteQueue("ph." + swarmId + ".mod");
            amqp.deleteQueue("ph." + swarmId + ".final");
            swarm.setStatus(SwarmStatus.STOPPED);
        });
    }
}
