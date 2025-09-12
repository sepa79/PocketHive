package io.pockethive.orchestrator.app;

import io.pockethive.Topology;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmRegistry;
import io.pockethive.orchestrator.domain.SwarmStatus;
import io.pockethive.orchestrator.domain.SwarmTemplate;
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
        log.debug("docker env: {}", env);
        String containerId = docker.createAndStartContainer(image, env);
        log.info("controller container {} started for swarm {}", containerId, swarmId);
        Swarm swarm = new Swarm(swarmId, instanceId, containerId);
        swarm.setStatus(SwarmStatus.RUNNING);
        registry.register(swarm);
        return swarm;
    }

    public void stopSwarm(String swarmId) {
        registry.find(swarmId).ifPresent(swarm -> {
            log.info("stopping controller container {} for swarm {}", swarm.getContainerId(), swarmId);
            docker.stopAndRemoveContainer(swarm.getContainerId());
            amqp.deleteQueue("ph." + swarmId + ".gen");
            amqp.deleteQueue("ph." + swarmId + ".mod");
            amqp.deleteQueue("ph." + swarmId + ".final");
            swarm.setStatus(SwarmStatus.STOPPED);
        });
    }
}
