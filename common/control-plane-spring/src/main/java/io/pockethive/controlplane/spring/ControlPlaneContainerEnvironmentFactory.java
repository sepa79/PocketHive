package io.pockethive.controlplane.spring;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.autoconfigure.amqp.RabbitProperties;

/**
 * Builds environment maps for control-plane participants so services share a consistent
 * contract when the orchestrator launches controller and worker containers.
 */
public final class ControlPlaneContainerEnvironmentFactory {

    private static final String DEFAULT_CONTROL_QUEUE_PREFIX = "ph.control";
    private static final String DEFAULT_PUSHGATEWAY_SHUTDOWN_OPERATION = "DELETE";

    private ControlPlaneContainerEnvironmentFactory() {
    }

    public static Map<String, String> controllerEnvironment(String swarmId,
                                                            String instanceId,
                                                            String managerRole,
                                                            ControlPlaneProperties controlPlaneProperties,
                                                            ControllerSettings settings,
                                                            RabbitProperties rabbitProperties) {
        String resolvedSwarmId = requireArgument(swarmId, "swarmId");
        String resolvedInstance = requireArgument(instanceId, "controller instance");
        Objects.requireNonNull(settings, "settings");

        Map<String, String> env = new LinkedHashMap<>();
        env.put("POCKETHIVE_CONTROL_PLANE_INSTANCE_ID", resolvedInstance);
        env.put(
            "POCKETHIVE_CONTROL_PLANE_EXCHANGE",
            requireSetting(controlPlaneProperties.getExchange(), "pockethive.control-plane.exchange"));
        env.put("POCKETHIVE_CONTROL_PLANE_SWARM_ID", resolvedSwarmId);
        populateRabbitEnv(env, rabbitProperties);
        env.put(
            "POCKETHIVE_LOGS_EXCHANGE",
            requireSetting(settings.logsExchange(), "pockethive.control-plane.orchestrator.rabbit.logs-exchange"));
        env.put("POCKETHIVE_CONTROL_PLANE_WORKER_ENABLED",
            Boolean.toString(controlPlaneProperties.getWorker().isEnabled()));
        env.put("POCKETHIVE_CONTROL_PLANE_MANAGER_ROLE", requireSetting(managerRole, "pockethive.control-plane.manager.role"));
        env.put(
            "POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_CONTROL_QUEUE_PREFIX",
            resolveControlQueuePrefix(controlPlaneProperties.getExchange()));
        String trafficPrefix = settings.trafficQueuePrefix() != null && !settings.trafficQueuePrefix().isBlank()
            ? settings.trafficQueuePrefix()
            : "ph." + resolvedSwarmId;
        env.put("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_TRAFFIC_QUEUE_PREFIX", trafficPrefix);
        String hiveExchange = settings.trafficHiveExchange() != null && !settings.trafficHiveExchange().isBlank()
            ? settings.trafficHiveExchange()
            : trafficPrefix + ".hive";
        env.put("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_TRAFFIC_HIVE_EXCHANGE", hiveExchange);
        env.put(
            "POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_RABBIT_LOGS_EXCHANGE",
            requireSetting(settings.logsExchange(), "pockethive.control-plane.orchestrator.rabbit.logs-exchange"));
        env.put(
            "POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_RABBIT_LOGGING_ENABLED",
            Boolean.toString(settings.loggingEnabled()));
        env.put(
            "POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_PUSHGATEWAY_ENABLED",
            Boolean.toString(settings.metricsEnabled()));
        env.put(
            "POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_PUSHGATEWAY_PUSH_RATE",
            settings.metricsPushRate().toString());
        env.put(
            "POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_PUSHGATEWAY_SHUTDOWN_OPERATION",
            resolvePushgatewayShutdownOperation(settings.metricsShutdownOperation()));
        if (settings.metricsBaseUrl() != null && !settings.metricsBaseUrl().isBlank()) {
            env.put("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_PUSHGATEWAY_BASE_URL", settings.metricsBaseUrl());
        }
        env.put(
            "POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_DOCKER_SOCKET_PATH",
            requireSetting(settings.dockerSocketPath(), "pockethive.control-plane.orchestrator.docker.socket-path"));
        applyPushgatewayExport(env,
            settings.metricsEnabled(),
            settings.metricsBaseUrl(),
            settings.metricsPushRate().toString(),
            settings.metricsShutdownOperation(),
            resolvedSwarmId,
            null);
        return env;
    }

    public static Map<String, String> workerEnvironment(String instanceId,
                                                        WorkerSettings settings,
                                                        RabbitProperties rabbitProperties) {
        String resolvedInstance = requireArgument(instanceId, "worker instance");
        Objects.requireNonNull(settings, "settings");
        Map<String, String> env = new LinkedHashMap<>();
        env.put("POCKETHIVE_CONTROL_PLANE_INSTANCE_ID", resolvedInstance);
        env.put("POCKETHIVE_CONTROL_PLANE_SWARM_ID", requireSetting(settings.swarmId(), "pockethive.control-plane.swarm-id"));
        env.put(
            "POCKETHIVE_CONTROL_PLANE_EXCHANGE",
            requireSetting(settings.controlExchange(), "pockethive.control-plane.exchange"));
        env.put(
            "POCKETHIVE_CONTROL_PLANE_TRAFFIC_EXCHANGE",
            requireSetting(settings.hiveExchange(), "pockethive.control-plane.traffic-exchange"));
        populateRabbitEnv(env, rabbitProperties);
        env.put(
            "POCKETHIVE_LOGS_EXCHANGE",
            requireSetting(settings.logsExchange(), "pockethive.control-plane.swarm-controller.rabbit.logs-exchange"));
        env.put(
            "POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_RABBIT_LOGS_EXCHANGE",
            requireSetting(settings.logsExchange(), "pockethive.control-plane.swarm-controller.rabbit.logs-exchange"));
        env.put(
            "POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_RABBIT_LOGGING_ENABLED",
            Boolean.toString(settings.loggingEnabled()));
        String queuePrefix = settings.trafficQueuePrefix();
        env.put("POCKETHIVE_CONTROL_PLANE_QUEUES_GENERATOR", queuePrefix + ".gen");
        env.put("POCKETHIVE_CONTROL_PLANE_QUEUES_MODERATOR", queuePrefix + ".mod");
        env.put("POCKETHIVE_CONTROL_PLANE_QUEUES_FINAL", queuePrefix + ".final");
        env.put(
            "POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_PUSHGATEWAY_ENABLED",
            Boolean.toString(settings.metricsEnabled()));
        env.put(
            "POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_PUSHGATEWAY_PUSH_RATE",
            settings.metricsPushRate().toString());
        env.put(
            "POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_PUSHGATEWAY_SHUTDOWN_OPERATION",
            resolvePushgatewayShutdownOperation(settings.metricsShutdownOperation()));
        if (settings.metricsBaseUrl() != null && !settings.metricsBaseUrl().isBlank()) {
            env.put("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_PUSHGATEWAY_BASE_URL", settings.metricsBaseUrl());
        }
        applyPushgatewayExport(env,
            settings.metricsEnabled(),
            settings.metricsBaseUrl(),
            settings.metricsPushRate().toString(),
            settings.metricsShutdownOperation(),
            settings.swarmId(),
            resolvedInstance);
        return env;
    }

    private static void populateRabbitEnv(Map<String, String> env, RabbitProperties rabbitProperties) {
        Objects.requireNonNull(rabbitProperties, "rabbitProperties");
        env.put("SPRING_RABBITMQ_HOST",
            requireSetting(rabbitProperties.getHost(), "spring.rabbitmq.host"));
        env.put("SPRING_RABBITMQ_PORT", requireRabbitPort(rabbitProperties));
        env.put("SPRING_RABBITMQ_USERNAME",
            requireSetting(rabbitProperties.getUsername(), "spring.rabbitmq.username"));
        env.put("SPRING_RABBITMQ_PASSWORD",
            requireSetting(rabbitProperties.getPassword(), "spring.rabbitmq.password"));
        env.put("SPRING_RABBITMQ_VIRTUAL_HOST",
            requireSetting(rabbitProperties.getVirtualHost(), "spring.rabbitmq.virtual-host"));
    }

    private static void applyPushgatewayExport(Map<String, String> env,
                                               boolean enabled,
                                               String baseUrl,
                                               String pushRate,
                                               String shutdownOperation,
                                               String swarmId,
                                               String instanceId) {
        if (!enabled || baseUrl == null || baseUrl.isBlank()) {
            return;
        }
        env.put("MANAGEMENT_PROMETHEUS_METRICS_EXPORT_PUSHGATEWAY_ENABLED", Boolean.toString(enabled));
        env.put("MANAGEMENT_PROMETHEUS_METRICS_EXPORT_PUSHGATEWAY_BASE_URL", baseUrl);
        env.put("MANAGEMENT_PROMETHEUS_METRICS_EXPORT_PUSHGATEWAY_PUSH_RATE", pushRate);
        if (shutdownOperation != null && !shutdownOperation.isBlank()) {
            env.put("MANAGEMENT_PROMETHEUS_METRICS_EXPORT_PUSHGATEWAY_SHUTDOWN_OPERATION", shutdownOperation);
        }
        env.put("MANAGEMENT_PROMETHEUS_METRICS_EXPORT_PUSHGATEWAY_JOB", swarmId);
        if (instanceId != null) {
            env.put("MANAGEMENT_PROMETHEUS_METRICS_EXPORT_PUSHGATEWAY_GROUPING_KEY_INSTANCE", instanceId);
        }
    }

    private static String resolveControlQueuePrefix(String controlExchange) {
        if (controlExchange == null || controlExchange.isBlank()) {
            return DEFAULT_CONTROL_QUEUE_PREFIX;
        }
        return controlExchange;
    }

    private static String resolvePushgatewayShutdownOperation(String shutdownOperation) {
        if (shutdownOperation == null || shutdownOperation.isBlank()) {
            return DEFAULT_PUSHGATEWAY_SHUTDOWN_OPERATION;
        }
        return shutdownOperation;
    }

    private static String requireSetting(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(propertyName + " must not be null or blank");
        }
        return value;
    }

    private static String requireRabbitPort(RabbitProperties properties) {
        Integer port = properties.getPort();
        if (port == null || port <= 0) {
            throw new IllegalStateException("spring.rabbitmq.port must be a positive integer");
        }
        return Integer.toString(port);
    }

    private static String requireArgument(String value, String description) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(description + " must not be null or blank");
        }
        return value;
    }

    public record ControllerSettings(String logsExchange,
                                     boolean loggingEnabled,
                                     boolean metricsEnabled,
                                     String metricsBaseUrl,
                                     Duration metricsPushRate,
                                     String metricsShutdownOperation,
                                     String dockerSocketPath,
                                     String trafficQueuePrefix,
                                     String trafficHiveExchange) {
        public ControllerSettings {
            Objects.requireNonNull(metricsPushRate, "metricsPushRate");
            requireArgument(logsExchange, "logsExchange");
            requireArgument(dockerSocketPath, "dockerSocketPath");
        }
    }

    public record WorkerSettings(String swarmId,
                                 String controlExchange,
                                 String trafficQueuePrefix,
                                 String hiveExchange,
                                 String logsExchange,
                                 boolean loggingEnabled,
                                 boolean metricsEnabled,
                                 String metricsBaseUrl,
                                 Duration metricsPushRate,
                                 String metricsShutdownOperation) {
        public WorkerSettings {
            Objects.requireNonNull(metricsPushRate, "metricsPushRate");
            requireArgument(swarmId, "swarmId");
            requireArgument(controlExchange, "controlExchange");
            requireArgument(trafficQueuePrefix, "trafficQueuePrefix");
            requireArgument(hiveExchange, "hiveExchange");
            requireArgument(logsExchange, "logsExchange");
        }
    }
}
