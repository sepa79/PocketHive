package io.pockethive.orchestrator.app;

import com.github.dockerjava.api.model.Bind;
import io.pockethive.controlplane.spring.ControlPlaneContainerEnvironmentFactory;
import io.pockethive.controlplane.spring.ControlPlaneProperties;
import io.pockethive.docker.DockerContainerClient;
import io.pockethive.orchestrator.config.OrchestratorProperties;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmRegistry;
import io.pockethive.orchestrator.domain.SwarmStatus;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.boot.autoconfigure.amqp.RabbitProperties;
import org.springframework.stereotype.Service;

@Service
public class ContainerLifecycleManager {
    private static final Logger log = LoggerFactory.getLogger(ContainerLifecycleManager.class);
    private static final String SWARM_CONTROLLER_ROLE = "swarm-controller";
    private final DockerContainerClient docker;
    private final SwarmRegistry registry;
    private final AmqpAdmin amqp;
    private final OrchestratorProperties properties;
    private final ControlPlaneProperties controlPlaneProperties;
    private final RabbitProperties rabbitProperties;

    public ContainerLifecycleManager(
        DockerContainerClient docker,
        SwarmRegistry registry,
        AmqpAdmin amqp,
        OrchestratorProperties properties,
        ControlPlaneProperties controlPlaneProperties,
        RabbitProperties rabbitProperties) {
        this.docker = Objects.requireNonNull(docker, "docker");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.amqp = Objects.requireNonNull(amqp, "amqp");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.controlPlaneProperties = Objects.requireNonNull(controlPlaneProperties, "controlPlaneProperties");
        this.rabbitProperties = Objects.requireNonNull(rabbitProperties, "rabbitProperties");
    }

    public Swarm startSwarm(String swarmId, String image, String instanceId) {
        String resolvedInstance = requireNonBlank(instanceId, "controller instance");
        String resolvedSwarmId = requireNonBlank(swarmId, "swarmId");
        ControlPlaneContainerEnvironmentFactory.ControllerSettings controllerSettings =
            new ControlPlaneContainerEnvironmentFactory.ControllerSettings(
                properties.getRabbit().getLogsExchange(),
                properties.getRabbit().getLogging().isEnabled(),
                properties.getDocker().getSocketPath(),
                "ph." + resolvedSwarmId,
                "ph." + resolvedSwarmId + ".hive");
        Map<String, String> env = new LinkedHashMap<>(
            ControlPlaneContainerEnvironmentFactory.controllerEnvironment(
                resolvedSwarmId,
                resolvedInstance,
                SWARM_CONTROLLER_ROLE,
                controlPlaneProperties,
                controllerSettings,
                rabbitProperties));
        String net = docker.resolveControlNetwork();
        if (net != null && !net.isBlank()) {
            env.put("CONTROL_NETWORK", net);
        }
        String dockerSocket = properties.getDocker().getSocketPath();
        env.put("DOCKER_SOCKET_PATH", dockerSocket);
        env.put("DOCKER_HOST", "unix://" + dockerSocket);
        log.info("launching controller for swarm {} as instance {} using image {}", resolvedSwarmId, resolvedInstance, image);
        log.info("docker env: {}", env);
        String containerId = docker.createAndStartContainer(
            image,
            env,
            resolvedInstance,
            hostConfig -> hostConfig.withBinds(Bind.parse(dockerSocket + ":" + dockerSocket)));
        log.info("controller container {} ({}) started for swarm {}", containerId, resolvedInstance, resolvedSwarmId);
        Swarm swarm = new Swarm(resolvedSwarmId, resolvedInstance, containerId);
        registry.register(swarm);
        registry.updateStatus(resolvedSwarmId, SwarmStatus.CREATING);
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

    private static String requireNonBlank(String value, String description) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(description + " must not be null or blank");
        }
        return value;
    }
}
