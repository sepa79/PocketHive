package io.pockethive.controlplane.spring;

import io.pockethive.observability.metrics.PocketHiveMetricsAdapter;
import io.pockethive.sink.clickhouse.metrics.ClickHouseMetricsSinkProperties;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.autoconfigure.amqp.RabbitProperties;

/**
 * Builds environment maps for control-plane participants so services share a consistent
 * contract when the orchestrator launches controller and worker containers.
 */
public final class ControlPlaneContainerEnvironmentFactory {

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
        env.put("POCKETHIVE_CONTROL_PLANE_WORKER_ENABLED",
            Boolean.toString(controlPlaneProperties.getWorker().isEnabled()));
        env.put("POCKETHIVE_CONTROL_PLANE_MANAGER_ROLE", requireSetting(managerRole, "pockethive.control-plane.manager.role"));
        env.put(
            "POCKETHIVE_CONTROL_PLANE_CONTROL_QUEUE_PREFIX",
            requireSetting(controlPlaneProperties.getControlQueuePrefix(),
                "pockethive.control-plane.control-queue-prefix"));
        String trafficPrefix = settings.trafficQueuePrefix() != null && !settings.trafficQueuePrefix().isBlank()
            ? settings.trafficQueuePrefix()
            : "ph." + resolvedSwarmId;
        env.put("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_TRAFFIC_QUEUE_PREFIX", trafficPrefix);
        String hiveExchange = settings.trafficHiveExchange() != null && !settings.trafficHiveExchange().isBlank()
            ? settings.trafficHiveExchange()
            : trafficPrefix + ".hive";
        env.put("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_TRAFFIC_HIVE_EXCHANGE", hiveExchange);
        applyPocketHiveMetricsSettings(
            env,
            settings.metrics(),
            resolvedSwarmId,
            settings.runId(),
            managerRole,
            resolvedInstance);
        applyControlPlaneMetricsSettings(env, settings.metrics());
        env.put(
            "POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_DOCKER_SOCKET_PATH",
            requireSetting(settings.dockerSocketPath(), "pockethive.control-plane.orchestrator.docker.socket-path"));
        return env;
    }

    public static Map<String, String> workerEnvironment(String instanceId,
                                                        String role,
                                                        WorkerSettings settings,
                                                        RabbitProperties rabbitProperties) {
        String resolvedInstance = requireArgument(instanceId, "worker instance");
        String resolvedRole = requireArgument(role, "worker role");
        Objects.requireNonNull(settings, "settings");
        Map<String, String> env = new LinkedHashMap<>();
        env.put("POCKETHIVE_CONTROL_PLANE_INSTANCE_ID", resolvedInstance);
        env.put("POCKETHIVE_CONTROL_PLANE_WORKER_ROLE", resolvedRole);
        env.put("POCKETHIVE_CONTROL_PLANE_SWARM_ID", requireSetting(settings.swarmId(), "pockethive.control-plane.swarm-id"));
        env.put(
            "POCKETHIVE_CONTROL_PLANE_EXCHANGE",
            requireSetting(settings.controlExchange(), "pockethive.control-plane.exchange"));
        populateRabbitEnv(env, rabbitProperties);
        env.put(
            "POCKETHIVE_CONTROL_PLANE_CONTROL_QUEUE_PREFIX",
            requireSetting(settings.controlQueuePrefix(), "pockethive.control-plane.control-queue-prefix"));
        applyPocketHiveMetricsSettings(
            env,
            settings.metrics(),
            settings.swarmId(),
            settings.runId(),
            resolvedRole,
            resolvedInstance);
        return env;
    }

    public static String swarmTrafficQueueName(String queuePrefix, String suffix) {
        return requireArgument(queuePrefix, "traffic queue prefix")
            + "."
            + requireArgument(suffix, "traffic queue suffix");
    }

    public static List<String> swarmTrafficQueueNames(String queuePrefix, Collection<String> suffixes) {
        if (suffixes == null || suffixes.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (String suffix : suffixes) {
            names.add(swarmTrafficQueueName(queuePrefix, suffix));
        }
        return List.copyOf(names);
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

    private static void applyControlPlaneMetricsSettings(
        Map<String, String> env,
        MetricsSettings metrics) {
        Objects.requireNonNull(metrics, "metrics");
        env.put(
            "POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_ADAPTER",
            metrics.adapter().name());
        env.put(
            "POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_PUBLISH_INTERVAL",
            metrics.publishInterval().toString());
        applyClickHouseControlPlaneSettings(env, metrics.clickHouse());
    }

    private static void applyPocketHiveMetricsSettings(Map<String, String> env,
                                                       MetricsSettings metrics,
                                                       String swarmId,
                                                       String runId,
                                                       String role,
                                                       String instance) {
        Objects.requireNonNull(metrics, "metrics");
        env.put("POCKETHIVE_METRICS_ADAPTER", metrics.adapter().name());
        env.put("POCKETHIVE_METRICS_PUBLISH_INTERVAL", metrics.publishInterval().toString());
        env.put("POCKETHIVE_METRICS_SWARM_ID", requireSetting(swarmId, "pockethive.metrics.swarm-id"));
        env.put("POCKETHIVE_METRICS_RUN_ID", requireSetting(runId, "pockethive.metrics.run-id"));
        env.put("POCKETHIVE_METRICS_ROLE", requireSetting(role, "pockethive.metrics.role"));
        env.put("POCKETHIVE_METRICS_INSTANCE", requireSetting(instance, "pockethive.metrics.instance"));
        applyClickHouseMetricsExport(env, metrics.clickHouse());
    }

    private static void applyClickHouseControlPlaneSettings(Map<String, String> env,
                                                            ClickHouseMetricsSinkProperties clickHouse) {
        if (!clickHouse.configured()) {
            return;
        }
        env.put("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_CLICKHOUSE_ENDPOINT", clickHouse.getEndpoint());
        env.put("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_CLICKHOUSE_TABLE", clickHouse.getTable());
        putIfNotBlank(env, "POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_CLICKHOUSE_USERNAME",
            clickHouse.getUsername());
        putIfNotBlank(env, "POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_CLICKHOUSE_PASSWORD",
            clickHouse.getPassword());
        env.put("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_CLICKHOUSE_CONNECT_TIMEOUT_MS",
            Integer.toString(clickHouse.getConnectTimeoutMs()));
        env.put("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_CLICKHOUSE_READ_TIMEOUT_MS",
            Integer.toString(clickHouse.getReadTimeoutMs()));
        env.put("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_CLICKHOUSE_BATCH_SIZE",
            Integer.toString(clickHouse.getBatchSize()));
        env.put("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_CLICKHOUSE_FLUSH_INTERVAL_MS",
            Integer.toString(clickHouse.getFlushIntervalMs()));
        env.put("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_CLICKHOUSE_MAX_BUFFERED_SAMPLES",
            Integer.toString(clickHouse.getMaxBufferedSamples()));
        env.put("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_CLICKHOUSE_MAX_LABEL_COUNT",
            Integer.toString(clickHouse.getMaxLabelCount()));
        env.put("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_CLICKHOUSE_MAX_LABEL_KEY_LENGTH",
            Integer.toString(clickHouse.getMaxLabelKeyLength()));
        env.put("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_CLICKHOUSE_MAX_LABEL_VALUE_LENGTH",
            Integer.toString(clickHouse.getMaxLabelValueLength()));
    }

    private static void applyClickHouseMetricsExport(Map<String, String> env,
                                                     ClickHouseMetricsSinkProperties clickHouse) {
        if (!clickHouse.configured()) {
            return;
        }
        env.put("POCKETHIVE_METRICS_CLICKHOUSE_ENDPOINT", clickHouse.getEndpoint());
        env.put("POCKETHIVE_METRICS_CLICKHOUSE_TABLE", clickHouse.getTable());
        putIfNotBlank(env, "POCKETHIVE_METRICS_CLICKHOUSE_USERNAME", clickHouse.getUsername());
        putIfNotBlank(env, "POCKETHIVE_METRICS_CLICKHOUSE_PASSWORD", clickHouse.getPassword());
        env.put("POCKETHIVE_METRICS_CLICKHOUSE_CONNECT_TIMEOUT_MS", Integer.toString(clickHouse.getConnectTimeoutMs()));
        env.put("POCKETHIVE_METRICS_CLICKHOUSE_READ_TIMEOUT_MS", Integer.toString(clickHouse.getReadTimeoutMs()));
        env.put("POCKETHIVE_METRICS_CLICKHOUSE_BATCH_SIZE", Integer.toString(clickHouse.getBatchSize()));
        env.put("POCKETHIVE_METRICS_CLICKHOUSE_FLUSH_INTERVAL_MS", Integer.toString(clickHouse.getFlushIntervalMs()));
        env.put("POCKETHIVE_METRICS_CLICKHOUSE_MAX_BUFFERED_SAMPLES",
            Integer.toString(clickHouse.getMaxBufferedSamples()));
        env.put("POCKETHIVE_METRICS_CLICKHOUSE_MAX_LABEL_COUNT", Integer.toString(clickHouse.getMaxLabelCount()));
        env.put("POCKETHIVE_METRICS_CLICKHOUSE_MAX_LABEL_KEY_LENGTH",
            Integer.toString(clickHouse.getMaxLabelKeyLength()));
        env.put("POCKETHIVE_METRICS_CLICKHOUSE_MAX_LABEL_VALUE_LENGTH",
            Integer.toString(clickHouse.getMaxLabelValueLength()));
    }

    private static void putIfNotBlank(Map<String, String> env, String key, String value) {
        if (value != null && !value.isBlank()) {
            env.put(key, value);
        }
    }

    private static String requireArgument(String value, String description) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(description + " must not be null or blank");
        }
        return value;
    }

    public record ControllerSettings(MetricsSettings metrics,
                                     String runId,
                                     String dockerSocketPath,
                                     String trafficQueuePrefix,
                                     String trafficHiveExchange) {
        public ControllerSettings {
            Objects.requireNonNull(metrics, "metrics");
            requireArgument(runId, "runId");
            requireArgument(dockerSocketPath, "dockerSocketPath");
        }

        public String trafficQueueName(String suffix) {
            return swarmTrafficQueueName(trafficQueuePrefix, suffix);
        }

        public List<String> trafficQueueNames(Collection<String> suffixes) {
            return swarmTrafficQueueNames(trafficQueuePrefix, suffixes);
        }
    }

    public record WorkerSettings(String swarmId,
                                 String runId,
                                 String controlExchange,
                                 String controlQueuePrefix,
                                 String hiveExchange,
                                 MetricsSettings metrics) {
        public WorkerSettings {
            Objects.requireNonNull(metrics, "metrics");
            requireArgument(swarmId, "swarmId");
            requireArgument(runId, "runId");
            requireArgument(controlExchange, "controlExchange");
            requireArgument(controlQueuePrefix, "controlQueuePrefix");
            requireArgument(hiveExchange, "hiveExchange");
        }
    }

    public record MetricsSettings(PocketHiveMetricsAdapter adapter,
                                  Duration publishInterval,
                                  ClickHouseMetricsSinkProperties clickHouse) {
        public MetricsSettings {
            Objects.requireNonNull(adapter, "adapter");
            Objects.requireNonNull(publishInterval, "publishInterval");
            clickHouse = clickHouse == null ? ClickHouseMetricsSinkProperties.disabled() : clickHouse;
            if (publishInterval.isZero() || publishInterval.isNegative()) {
                throw new IllegalArgumentException("metrics.publishInterval must be positive");
            }
            if (adapter == PocketHiveMetricsAdapter.CLICKHOUSE) {
                clickHouse.requireConfigured();
            }
        }
    }
}
