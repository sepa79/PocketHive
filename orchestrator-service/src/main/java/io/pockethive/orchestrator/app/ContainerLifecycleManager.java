package io.pockethive.orchestrator.app;

import com.github.dockerjava.api.model.Bind;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmRegistry;
import io.pockethive.orchestrator.domain.SwarmStatus;
import io.pockethive.docker.DockerContainerClient;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class ContainerLifecycleManager {
    private static final Logger log = LoggerFactory.getLogger(ContainerLifecycleManager.class);
    private final DockerContainerClient docker;
    private final SwarmRegistry registry;
    private final AmqpAdmin amqp;
    private final TopicExchange controlExchange;

    private static final String DEFAULT_DOCKER_SOCKET = "/var/run/docker.sock";

    public ContainerLifecycleManager(DockerContainerClient docker,
                                     SwarmRegistry registry,
                                     AmqpAdmin amqp,
                                     @Qualifier("controlPlaneExchange") TopicExchange controlExchange) {
        this.docker = docker;
        this.registry = registry;
        this.amqp = amqp;
        this.controlExchange = Objects.requireNonNull(controlExchange, "controlExchange");
    }

    public Swarm startSwarm(String swarmId, String image, String instanceId) {
        java.util.Map<String, String> env = new java.util.HashMap<>();
        env.put("POCKETHIVE_CONTROL_PLANE_INSTANCE_ID", instanceId);
        env.put("POCKETHIVE_CONTROL_PLANE_EXCHANGE", controlExchange.getName());
        env.put("POCKETHIVE_CONTROL_PLANE_SWARM_ID", swarmId);
        env.put("RABBITMQ_HOST", java.util.Optional.ofNullable(System.getenv("RABBITMQ_HOST")).orElse("rabbitmq"));
        env.put(
            "POCKETHIVE_LOGS_EXCHANGE",
            java.util.Optional.ofNullable(System.getenv("POCKETHIVE_LOGS_EXCHANGE")).orElse("ph.logs"));
        applyPushgatewayEnv(env, swarmId);
        String net = docker.resolveControlNetwork();
        if (net != null && !net.isBlank()) {
            env.put("CONTROL_NETWORK", net);
        }
        String dockerSocket = resolveDockerSocketPath();
        env.put("DOCKER_SOCKET_PATH", dockerSocket);
        env.put("DOCKER_HOST", "unix://" + dockerSocket);
        log.info("launching controller for swarm {} as instance {} using image {}", swarmId, instanceId, image);
        log.info("docker env: {}", env);
        String containerId = docker.createAndStartContainer(
            image,
            env,
            instanceId,
            hostConfig -> hostConfig.withBinds(Bind.parse(dockerSocket + ":" + dockerSocket)));
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

    private static String resolveDockerSocketPath() {
        return java.util.Optional.ofNullable(System.getenv("DOCKER_SOCKET_PATH"))
            .filter(path -> !path.isBlank())
            .or(() -> java.util.Optional.ofNullable(System.getProperty("DOCKER_SOCKET_PATH")))
            .filter(path -> !path.isBlank())
            .orElse(DEFAULT_DOCKER_SOCKET);
    }

    protected java.util.Map<String, String> environment() {
        return System.getenv();
    }

    private void applyPushgatewayEnv(java.util.Map<String, String> env, String swarmId) {
        java.util.Map<String, String> source = environment();
        String baseUrl = source.get("MANAGEMENT_PROMETHEUS_METRICS_EXPORT_PUSHGATEWAY_BASE_URL");
        if (isBlank(baseUrl)) {
            return;
        }
        env.put("MANAGEMENT_PROMETHEUS_METRICS_EXPORT_PUSHGATEWAY_BASE_URL", baseUrl);
        copyIfPresent("MANAGEMENT_PROMETHEUS_METRICS_EXPORT_PUSHGATEWAY_ENABLED", source, env);
        copyIfPresent("MANAGEMENT_PROMETHEUS_METRICS_EXPORT_PUSHGATEWAY_PUSH_RATE", source, env);
        copyIfPresent("MANAGEMENT_PROMETHEUS_METRICS_EXPORT_PUSHGATEWAY_SHUTDOWN_OPERATION", source, env);
        env.put("MANAGEMENT_PROMETHEUS_METRICS_EXPORT_PUSHGATEWAY_JOB", swarmId);
    }

    private static void copyIfPresent(String key, java.util.Map<String, String> source, java.util.Map<String, String> target) {
        String value = source.get(key);
        if (!isBlank(value)) {
            target.put(key, value);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

}
