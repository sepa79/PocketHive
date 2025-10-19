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
    private static final String SWARM_CONTROLLER_ROLE = "swarm-controller";
    private static final String DEFAULT_CONTROL_QUEUE_PREFIX = "ph.control";
    private static final String DEFAULT_PUSHGATEWAY_SHUTDOWN_OPERATION = "DELETE";
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
        Map<String, String> env = new HashMap<>();
        env.put("POCKETHIVE_CONTROL_PLANE_INSTANCE_ID", resolvedInstance);
        String controlExchange = requireControlPlaneSetting(
            controlPlaneProperties.getExchange(), "pockethive.control-plane.exchange");
        env.put("POCKETHIVE_CONTROL_PLANE_EXCHANGE", controlExchange);
        env.put("POCKETHIVE_CONTROL_PLANE_SWARM_ID", resolvedSwarmId);
        String rabbitHost = requireRabbitSetting(rabbitProperties.getHost(), "spring.rabbitmq.host");
        String rabbitPort = Integer.toString(requireRabbitPort(rabbitProperties));
        String rabbitUser = requireRabbitSetting(rabbitProperties.getUsername(), "spring.rabbitmq.username");
        String rabbitPass = requireRabbitSetting(rabbitProperties.getPassword(), "spring.rabbitmq.password");
        String rabbitVhost = requireRabbitSetting(rabbitProperties.getVirtualHost(), "spring.rabbitmq.virtual-host");
        env.put("RABBITMQ_HOST", rabbitHost);
        env.put("RABBITMQ_PORT", rabbitPort);
        env.put("RABBITMQ_DEFAULT_USER", rabbitUser);
        env.put("RABBITMQ_DEFAULT_PASS", rabbitPass);
        env.put("RABBITMQ_VHOST", rabbitVhost);
        env.put("SPRING_RABBITMQ_HOST", rabbitHost);
        env.put("SPRING_RABBITMQ_PORT", rabbitPort);
        env.put("SPRING_RABBITMQ_USERNAME", rabbitUser);
        env.put("SPRING_RABBITMQ_PASSWORD", rabbitPass);
        env.put("SPRING_RABBITMQ_VIRTUAL_HOST", rabbitVhost);
        env.put(
            "POCKETHIVE_LOGS_EXCHANGE",
            requireRabbitSetting(
                properties.getRabbit().getLogsExchange(),
                "pockethive.control-plane.orchestrator.rabbit.logs-exchange"));
        populateSwarmControllerEnv(env, resolvedSwarmId, controlExchange, rabbitHost);
        applyPushgatewayEnv(env, resolvedSwarmId);
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

    private void populateSwarmControllerEnv(Map<String, String> env,
                                            String swarmId,
                                            String controlExchange,
                                            String rabbitHost) {
        env.put("POCKETHIVE_CONTROL_PLANE_WORKER_ENABLED",
            Boolean.toString(controlPlaneProperties.getWorker().isEnabled()));
        env.put("POCKETHIVE_CONTROL_PLANE_MANAGER_ROLE", SWARM_CONTROLLER_ROLE);
        env.put("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_CONTROL_QUEUE_PREFIX",
            resolveControlQueuePrefix(controlExchange));
        String trafficPrefix = "ph." + swarmId;
        env.put("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_TRAFFIC_QUEUE_PREFIX", trafficPrefix);
        env.put("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_TRAFFIC_HIVE_EXCHANGE", trafficPrefix + ".hive");
        env.put("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_RABBIT_HOST", rabbitHost);
        env.put("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_RABBIT_LOGS_EXCHANGE",
            requireRabbitSetting(
                properties.getRabbit().getLogsExchange(),
                "pockethive.control-plane.orchestrator.rabbit.logs-exchange"));
        env.put("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_RABBIT_LOGGING_ENABLED",
            Boolean.toString(properties.getRabbit().getLogging().isEnabled()));
        OrchestratorProperties.Pushgateway pushgateway = properties.getPushgateway();
        env.put("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_PUSHGATEWAY_ENABLED",
            Boolean.toString(pushgateway.isEnabled()));
        env.put("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_PUSHGATEWAY_PUSH_RATE",
            pushgateway.getPushRate().toString());
        env.put(
            "POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_PUSHGATEWAY_SHUTDOWN_OPERATION",
            resolvePushgatewayShutdownOperation(pushgateway));
        String socketPath = requireNonBlank(properties.getDocker().getSocketPath(),
            "pockethive.control-plane.orchestrator.docker.socket-path");
        env.put("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_DOCKER_SOCKET_PATH", socketPath);
    }

    private static String resolveControlQueuePrefix(String controlExchange) {
        if (controlExchange == null || controlExchange.isBlank()) {
            return DEFAULT_CONTROL_QUEUE_PREFIX;
        }
        return controlExchange;
    }

    private static String resolvePushgatewayShutdownOperation(OrchestratorProperties.Pushgateway pushgateway) {
        String shutdown = pushgateway.getShutdownOperation();
        if (shutdown == null || shutdown.isBlank()) {
            return DEFAULT_PUSHGATEWAY_SHUTDOWN_OPERATION;
        }
        return shutdown;
    }

    private static String requireNonBlank(String value, String description) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(description + " must not be null or blank");
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
}
