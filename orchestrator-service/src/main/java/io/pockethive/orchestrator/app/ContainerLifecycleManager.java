package io.pockethive.orchestrator.app;

import com.github.dockerjava.api.model.Bind;
import io.pockethive.controlplane.spring.ControlPlaneProperties;
import io.pockethive.docker.DockerContainerClient;
import io.pockethive.orchestrator.config.OrchestratorProperties;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmRegistry;
import io.pockethive.orchestrator.domain.SwarmStatus;
import java.util.HashMap;
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
        Map<String, String> env = new HashMap<>();
        String resolvedInstanceId = requireNonBlank(instanceId, "instanceId");
        String resolvedSwarmId = requireNonBlank(swarmId, "swarmId");
        env.put("POCKETHIVE_CONTROL_PLANE_INSTANCE_ID", resolvedInstanceId);
        String controlExchange = requireControlPlaneSetting(
            controlPlaneProperties.getExchange(), "pockethive.control-plane.exchange");
        env.put("POCKETHIVE_CONTROL_PLANE_EXCHANGE", controlExchange);
        env.put("POCKETHIVE_CONTROL_PLANE_SWARM_ID", resolvedSwarmId);
        env.put("POCKETHIVE_CONTROL_PLANE_WORKER_ENABLED", Boolean.FALSE.toString());
        env.put("POCKETHIVE_CONTROL_PLANE_MANAGER_ROLE", "swarm-controller");
        env.put(
            "POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_CONTROL_QUEUE_PREFIX",
            controlExchange);
        String trafficQueuePrefix = trafficQueuePrefix(resolvedSwarmId);
        env.put("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_TRAFFIC_QUEUE_PREFIX", trafficQueuePrefix);
        env.put(
            "POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_TRAFFIC_HIVE_EXCHANGE",
            trafficQueuePrefix + ".hive");
        String rabbitHost = requireRabbitSetting(rabbitProperties.getHost(), "spring.rabbitmq.host");
        env.put("RABBITMQ_HOST", rabbitHost);
        env.put("SPRING_RABBITMQ_HOST", rabbitHost);
        int rabbitPort = requireRabbitPort(rabbitProperties);
        env.put("RABBITMQ_PORT", Integer.toString(rabbitPort));
        env.put("SPRING_RABBITMQ_PORT", Integer.toString(rabbitPort));
        String rabbitUser = requireRabbitSetting(rabbitProperties.getUsername(), "spring.rabbitmq.username");
        env.put("RABBITMQ_DEFAULT_USER", rabbitUser);
        env.put("SPRING_RABBITMQ_USERNAME", rabbitUser);
        String rabbitPass = requireRabbitSetting(rabbitProperties.getPassword(), "spring.rabbitmq.password");
        env.put("RABBITMQ_DEFAULT_PASS", rabbitPass);
        env.put("SPRING_RABBITMQ_PASSWORD", rabbitPass);
        String rabbitVhost = requireRabbitSetting(rabbitProperties.getVirtualHost(), "spring.rabbitmq.virtual-host");
        env.put("RABBITMQ_VHOST", rabbitVhost);
        env.put("SPRING_RABBITMQ_VIRTUAL_HOST", rabbitVhost);
        env.put(
            "POCKETHIVE_LOGS_EXCHANGE",
            requireRabbitSetting(
                properties.getRabbit().getLogsExchange(),
                "pockethive.control-plane.orchestrator.rabbit.logs-exchange"));
        env.put(
            "POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_RABBIT_HOST",
            rabbitHost);
        env.put(
            "POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_RABBIT_LOGS_EXCHANGE",
            requireRabbitSetting(
                properties.getRabbit().getLogsExchange(),
                "pockethive.control-plane.orchestrator.rabbit.logs-exchange"));
        env.put(
            "POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_RABBIT_LOGGING_ENABLED",
            Boolean.toString(properties.getRabbit().getLogging().isEnabled()));
        OrchestratorProperties.Pushgateway pushgateway = properties.getPushgateway();
        env.put(
            "POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_PUSHGATEWAY_ENABLED",
            Boolean.toString(pushgateway.isEnabled()));
        env.put(
            "POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_PUSHGATEWAY_PUSH_RATE",
            pushgateway.getPushRate().toString());
        env.put(
            "POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_PUSHGATEWAY_SHUTDOWN_OPERATION",
            shutdownOperation(pushgateway));
        applyPushgatewayEnv(env, swarmId);
        String net = docker.resolveControlNetwork();
        if (net != null && !net.isBlank()) {
            env.put("CONTROL_NETWORK", net);
        }
        String dockerSocket = properties.getDocker().getSocketPath();
        env.put("DOCKER_SOCKET_PATH", dockerSocket);
        env.put("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_DOCKER_SOCKET_PATH", dockerSocket);
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

    private static String requireRabbitSetting(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(propertyName + " must not be null or blank");
        }
        return value;
    }

    private static int requireRabbitPort(RabbitProperties properties) {
        int port = properties.getPort();
        if (port <= 0) {
            throw new IllegalStateException("spring.rabbitmq.port must be greater than zero");
        }
        return port;
    }

    private static String requireControlPlaneSetting(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(propertyName + " must not be null or blank");
        }
        return value;
    }

    private void applyPushgatewayEnv(Map<String, String> env, String swarmId) {
        OrchestratorProperties.Pushgateway pushgateway = properties.getPushgateway();
        if (!pushgateway.isEnabled() || !pushgateway.hasBaseUrl()) {
            return;
        }
        env.put("MANAGEMENT_PROMETHEUS_METRICS_EXPORT_PUSHGATEWAY_ENABLED", Boolean.toString(pushgateway.isEnabled()));
        env.put("MANAGEMENT_PROMETHEUS_METRICS_EXPORT_PUSHGATEWAY_BASE_URL", pushgateway.getBaseUrl());
        env.put(
            "MANAGEMENT_PROMETHEUS_METRICS_EXPORT_PUSHGATEWAY_PUSH_RATE",
            pushgateway.getPushRate().toString());
        String shutdownOperation = pushgateway.getShutdownOperation();
        if (shutdownOperation != null && !shutdownOperation.isBlank()) {
            env.put(
                "MANAGEMENT_PROMETHEUS_METRICS_EXPORT_PUSHGATEWAY_SHUTDOWN_OPERATION",
                shutdownOperation);
        }
        env.put("MANAGEMENT_PROMETHEUS_METRICS_EXPORT_PUSHGATEWAY_JOB", swarmId);
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be null or blank");
        }
        return value;
    }

    private static String trafficQueuePrefix(String swarmId) {
        return "ph." + swarmId;
    }

    private static String shutdownOperation(OrchestratorProperties.Pushgateway pushgateway) {
        String operation = pushgateway.getShutdownOperation();
        if (operation == null || operation.isBlank()) {
            return "DELETE";
        }
        return operation;
    }
}
