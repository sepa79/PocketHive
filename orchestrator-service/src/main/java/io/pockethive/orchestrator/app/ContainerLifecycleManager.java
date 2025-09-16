package io.pockethive.orchestrator.app;

import io.pockethive.Topology;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmRegistry;
import io.pockethive.orchestrator.domain.SwarmStatus;
import io.pockethive.docker.DockerContainerClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.stereotype.Service;

@Service
public class ContainerLifecycleManager {
    private static final Logger log = LoggerFactory.getLogger(ContainerLifecycleManager.class);
    private final DockerContainerClient docker;
    private final SwarmRegistry registry;
    private final AmqpAdmin amqp;

    public ContainerLifecycleManager(DockerContainerClient docker, SwarmRegistry registry, AmqpAdmin amqp) {
        this.docker = docker;
        this.registry = registry;
        this.amqp = amqp;
    }

    public Swarm startSwarm(String swarmId, String image, String instanceId) {
        java.util.Map<String, String> env = new java.util.HashMap<>();
        env.put("JAVA_TOOL_OPTIONS", "-Dbee.name=" + instanceId);
        env.put("PH_CONTROL_EXCHANGE", Topology.CONTROL_EXCHANGE);
        env.put("RABBITMQ_HOST", java.util.Optional.ofNullable(System.getenv("RABBITMQ_HOST")).orElse("rabbitmq"));
        env.put("PH_LOGS_EXCHANGE", java.util.Optional.ofNullable(System.getenv("PH_LOGS_EXCHANGE")).orElse("ph.logs"));
        env.put("PH_SWARM_ID", swarmId);
        String net = docker.resolveControlNetwork();
        if (net != null && !net.isBlank()) {
            env.put("CONTROL_NETWORK", net);
        }
        log.info("launching controller for swarm {} as instance {} using image {}", swarmId, instanceId, image);
        log.info("docker env: {}", env);
        String containerId = docker.createAndStartContainer(image, env, instanceId);
        log.info("controller container {} ({}) started for swarm {}", containerId, instanceId, swarmId);
        Swarm swarm = new Swarm(swarmId, instanceId, containerId);
        registry.register(swarm);
        registry.updateStatus(swarmId, SwarmStatus.CREATING);
        return swarm;
    }

    public void stopSwarm(String swarmId) {
        registry.find(swarmId).ifPresent(swarm -> {
            SwarmStatus current = swarm.getStatus();
            if (current == SwarmStatus.STOPPING || current == SwarmStatus.STOPPED) {
                log.info("swarm {} already {}", swarmId, current);
                return;
            }
            log.info("marking swarm {} as stopped", swarmId);
            registry.updateStatus(swarmId, SwarmStatus.STOPPING);
            registry.updateStatus(swarmId, SwarmStatus.STOPPED);
        });
    }

    public void removeSwarm(String swarmId) {
        registry.find(swarmId).ifPresent(swarm -> {
            log.info("tearing down controller container {} for swarm {}", swarm.getContainerId(), swarmId);
            registry.updateStatus(swarmId, SwarmStatus.REMOVING);
            docker.stopAndRemoveContainer(swarm.getContainerId());
            amqp.deleteQueue("ph." + swarmId + ".gen");
            amqp.deleteQueue("ph." + swarmId + ".mod");
            amqp.deleteQueue("ph." + swarmId + ".final");
            registry.updateStatus(swarmId, SwarmStatus.REMOVED);
            registry.remove(swarmId);
        });
    }
}
